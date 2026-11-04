// okay-go-game's Worker: its logic is Scala (okay.js, linked by `sbt serverJS/worker`); here only what JavaScript
// must say — the entry point and the Durable Object class, each call handed to the Scala worker
import { DurableObject } from "cloudflare:workers";
import { worker } from "./okay.js";

export class Game extends DurableObject {
  fetch(request) { return worker.objectFetch("GAMES", this.ctx, this.env, request); }
  alarm() { return worker.objectAlarm("GAMES", this.ctx, this.env); }
  webSocketMessage(ws, message) { return worker.objectMessage("GAMES", this.ctx, this.env, ws, message); }
  webSocketClose(ws) { return worker.objectClose("GAMES", this.ctx, this.env, ws); }
}

export default {
  fetch(request, env, ctx) { return worker.fetch(request, env, ctx); },
};
