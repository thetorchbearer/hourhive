// After deploying the API on Render, put its URL here (no trailing slash):
const RENDER_API = "https://hourhive-api.onrender.com";

window.HOURHIVE_API =
  location.hostname === "localhost" || location.hostname === "127.0.0.1"
    ? "http://localhost:8080/api"
    : RENDER_API + "/api";
