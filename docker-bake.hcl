# Multi-arch images for every service (the Mac builds arm64, the VM runs amd64).
#   docker buildx bake                          # current platform, into the local store
#   PLATFORMS=linux/amd64,linux/arm64 REGISTRY=ghcr.io/you/surge TAG=v1 \
#     docker buildx bake --push                 # both platforms, pushed

variable "REGISTRY"  { default = "surge" }
variable "TAG"       { default = "dev" }
variable "PLATFORMS" { default = "" }

group "default" {
  targets = ["gateway", "java", "python"]
}

target "_common" {
  platforms = PLATFORMS == "" ? [] : split(",", PLATFORMS)
}

target "gateway" {
  inherits = ["_common"]
  context  = "gateway"
  tags     = ["${REGISTRY}/gateway:${TAG}"]
}

target "java" {
  inherits   = ["_common"]
  name       = svc
  matrix     = { svc = ["admission", "inventory", "order"] }
  context    = "."
  dockerfile = "services/Dockerfile"
  args       = { SERVICE = svc }
  tags       = ["${REGISTRY}/${svc}:${TAG}"]
}

target "python" {
  inherits   = ["_common"]
  name       = svc
  matrix     = { svc = ["payment", "reconciler", "chaos"] }
  context    = svc
  dockerfile = "../infra/docker/python.Dockerfile"
  args       = { PACKAGE = "surge_${svc}" }
  tags       = ["${REGISTRY}/${svc}:${TAG}"]
}
