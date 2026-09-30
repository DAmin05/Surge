import { execSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

// Seeds a small fresh event for this run, so the test never competes with other
// data. The id reaches the tests through the environment.
export default function globalSetup() {
  const root = resolve(__dirname, "../..");
  const seed = readFileSync(resolve(root, "infra/postgres/seed.sql"));
  const out = execSync(
    `docker compose exec -T postgres psql -qtA -U orders_owner -d ${process.env.POSTGRES_DB ?? "surge"} ` +
      `-v sections=2 -v rows=2 -v seats_per_row=6 -v "name=E2E night"`,
    { cwd: root, input: seed },
  );
  const id = out.toString().trim().split("\n").pop();
  if (!id || !/^\d+$/.test(id)) throw new Error(`seed failed: ${out}`);
  process.env.E2E_EVENT_ID = id;
}
