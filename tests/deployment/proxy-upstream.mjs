import { createServer } from "node:http";

function respond(request, response) {
  if (request.url === "/api/v1/stream") {
    response.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-store" });
    response.flushHeaders();
    response.write("data: first\n\n");
    setTimeout(() => {
      response.end("data: second\n\n");
    }, 1500);
    return;
  }
  if (request.url === "/api/v1/file" && request.headers.range === "bytes=2-5") {
    response.writeHead(206, { "Content-Range": "bytes 2-5/10", "Content-Length": "4", "Accept-Ranges": "bytes" });
    response.end("2345");
    return;
  }
  response.writeHead(200, {
    "Content-Type": "application/json",
    "Set-Cookie": ["first=one; HttpOnly; SameSite=Lax", "second=two; HttpOnly; SameSite=Lax"],
  });
  response.end(JSON.stringify({ path: request.url, headers: request.headers }));
}

createServer(respond).listen(8080, "0.0.0.0");
createServer(respond).listen(3000, "0.0.0.0");
