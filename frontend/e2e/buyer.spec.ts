import { expect, test } from "@playwright/test";

// Week-4 exit criterion (browser half): a buyer completes a purchase in the browser,
// and a second browser watching the same event sees that seat go held, then sold,
// live over the WebSocket, without reloading.
test("a buyer checks out and a watcher sees the seat go held then sold", async ({ browser }) => {
  const eventId = process.env.E2E_EVENT_ID;
  expect(eventId, "global setup seeds an event").toBeTruthy();

  const buyer = await (await browser.newContext()).newPage();
  const watcher = await (await browser.newContext()).newPage();

  await watcher.goto(`/events/${eventId}`);
  await expect(watcher.getByTestId("live")).toHaveAttribute("data-connected", "true");

  await buyer.goto(`/events/${eventId}`);
  await expect(buyer.getByTestId("stage")).toHaveAttribute("data-stage", "pick");
  await expect(buyer.getByTestId("live")).toHaveAttribute("data-connected", "true");

  const seat = buyer.locator('rect[data-status="available"]').first();
  const seatId = await seat.getAttribute("data-seat");
  expect(seatId).toBeTruthy();
  await seat.click();
  await expect(buyer.locator(`rect[data-seat="${seatId}"]`)).toHaveAttribute("data-status", "selected");
  await buyer.getByTestId("hold").click();
  await expect(buyer.getByTestId("stage")).toHaveAttribute("data-stage", "held");
  await expect(buyer.locator(`rect[data-seat="${seatId}"]`)).toHaveAttribute("data-status", "mine");

  // The watcher never reloads: this is the live seat-event path.
  const watched = watcher.locator(`rect[data-seat="${seatId}"]`);
  await expect(watched).toHaveAttribute("data-status", "held");

  await buyer.getByTestId("pay").click();
  await expect(buyer.getByTestId("order")).toHaveAttribute("data-state", "CONFIRMED", { timeout: 60_000 });
  await expect(buyer.getByTestId("order")).toContainText("confirmed");

  await expect(watched).toHaveAttribute("data-status", "sold", { timeout: 30_000 });
});

test("the war room reports zero invariant violations", async ({ page }) => {
  await page.goto("/war-room");
  const hero = page.getByTestId("violations");
  await expect(hero).toHaveAttribute("data-total", "0", { timeout: 30_000 });
  await expect(hero).toContainText("Invariants hold");
});
