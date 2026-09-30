#!/bin/sh
# One-shot: form a 3-primary / 3-replica cluster from redis-1..6 if it isn't formed
# yet, then wait until every node reports cluster_state:ok and every replica is
# replicating. Safe to re-run.
set -eu
NODES="redis-1 redis-2 redis-3 redis-4 redis-5 redis-6"

for n in $NODES; do
  until redis-cli -h "$n" ping >/dev/null 2>&1; do sleep 0.5; done
done

if redis-cli -h redis-1 cluster info | grep -q 'cluster_known_nodes:6'; then
  echo "cluster already formed"
else
  args=""
  for n in $NODES; do args="$args $n:6379"; done
  # shellcheck disable=SC2086
  redis-cli --cluster create $args --cluster-replicas 1 --cluster-yes
fi

# Nodes gossip by IP. If they all restarted with new IPs (host reboot, daemon
# restart), nodes.conf only knows the old ones and no node can reach a peer. MEET
# re-introduces every node at its current address; it's a no-op for connected peers.
meet() {
  for a in $NODES; do
    for b in $NODES; do
      [ "$a" = "$b" ] && continue
      ip=$(getent hosts "$b" | awk '{print $1}')
      [ -n "$ip" ] && redis-cli -h "$a" cluster meet "$ip" 6379 >/dev/null 2>&1 || true
    done
  done
}

for n in $NODES; do
  tries=0
  until redis-cli -h "$n" cluster info | grep -q 'cluster_state:ok'; do
    tries=$((tries + 1))
    [ $((tries % 20)) -eq 0 ] && meet
    sleep 0.5
  done
done
# Replicas replicate from the address they last knew. After every node moved, the
# cluster can report ok while replicas sit at master_link_status:down, and a replica
# that isn't in sync is never promoted: a dead primary's slots stay down until it
# returns. CLUSTER REPLICATE re-points a replica at its primary's current address.
for n in $NODES; do
  if redis-cli -h "$n" info replication | grep -q 'master_link_status:down'; then
    primary=$(redis-cli -h "$n" cluster nodes | awk '/myself/ {print $4}')
    echo "$n: replication link down, re-pointing at $primary"
    redis-cli -h "$n" cluster replicate "$primary" >/dev/null || true
  fi
done
for n in $NODES; do
  tries=0
  while redis-cli -h "$n" info replication | grep -q 'master_link_status:down' && [ $tries -lt 120 ]; do
    tries=$((tries + 1))
    sleep 0.5
  done
done

redis-cli -h redis-1 cluster nodes
echo "cluster ready"
