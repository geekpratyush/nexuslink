# gRPC Client

Call gRPC services with **no `.proto` file** — NexusLink uses **server reflection**.

## Connect
- Enter the **host** and **port** (tick **TLS** for a secure channel).
- **Connect** lists the server's services via reflection.

## Invoke
1. Pick a **service** and a **method** (streaming methods are flagged).
2. Edit the **request** as JSON (a default template is pre-filled from the method's input type).
3. **Invoke** — the response is shown as JSON.

Unary methods are supported in this build; client/server/bidi streaming is on the roadmap.

> Tip: the *grpcb.in* sample (`grpcb.in:9000`) under **Samples (public)** is a public reflection-enabled test server.
