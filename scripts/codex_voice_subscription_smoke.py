#!/usr/bin/env python3
"""Linux-only subscription WebRTC smoke, no Codex process, emulator or API key.

Requirements: Python Playwright, local Chrome, espeak-ng and ffmpeg.
Run inside a resource-limited systemd user scope; no files or tokens leave the
temporary directory other than the deliberate subscription voice request.
This verifies transport and mocked Pi delegation, not Android microphone support.
"""
import argparse
import uuid
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import threading
import re
import websockets.sync.client
import urllib.error
import urllib.request
from playwright.sync_api import sync_playwright

CALL_URL = "https://chatgpt.com/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas"
INSTRUCTIONS = (
    "You are a voice interface for the external Pocket Agent. "
    "Every spoken user request must trigger a client delegation. Never "
    "independently answer, invent tool results, or finish a turn without "
    "waiting for the delegated agent result. When the client answers, "
    "speak that result. Pi alone executes phone tools."
)


def credentials(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    tokens = data.get("tokens") or data
    access = tokens.get("access_token") or tokens.get("accessToken")
    account = tokens.get("account_id") or tokens.get("accountId")
    if not access or not account:
        raise RuntimeError("A logged-in Codex ChatGPT account is required")
    return access, account


def create_call(offer, authfile):
    access, account = credentials(authfile)
    thread_id = str(uuid.uuid4())
    session = {
        "instructions": INSTRUCTIONS,
        "model": "gpt-live-1-codex",
        "audio": {"output": {"voice": "cove"}},
        "delegation": {"type": "client"},
    }
    body = json.dumps({"sdp": offer, "session": session}).encode()
    req = urllib.request.Request(
        CALL_URL, body, method="POST", headers={
            "Authorization": "Bearer " + access,
            "chatgpt-account-id": account,
            "originator": "pocket-agent-voice-probe",
            "User-Agent": "pocket-agent-voice-probe/0.163.0",
            "version": "0.163.0",
            "session-id": thread_id,
            "thread-id": thread_id,
            "x-session-id": thread_id,
            "openai-alpha": "quicksilver=v2",
            "Content-Type": "application/json",
            "Accept": "application/sdp",
        }
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            answer = response.read(131072).decode()
            if not answer.startswith("v=0"):
                raise RuntimeError("Subscription endpoint did not return SDP")
            location = response.headers.get("Location", "")
            call_id = location.split("?")[0].rstrip("/").split("/")[-1]
            if not re.fullmatch(r"rtc_[A-Za-z0-9_-]{3,128}|[0-9a-fA-F-]{36}", call_id):
                raise RuntimeError("Subscription endpoint returned no valid call id")
            return answer, call_id
    except urllib.error.HTTPError as exc:
        # Never include provider response bodies or auth-bearing URLs in logs.
        raise RuntimeError("Subscription WebRTC request rejected (HTTP %d)" % exc.code) from None


def run(authfile):
    with tempfile.TemporaryDirectory(prefix="pocket-subscription-voice-") as tmp:
        raw = os.path.join(tmp, "input.wav")
        wav = os.path.join(tmp, "input-48k.wav")
        page_path = os.path.join(tmp, "rtc.html")
        Path(page_path).write_text("<!doctype html><title>Voice smoke</title>", encoding="utf-8")
        subprocess.run(
            ["espeak-ng", "-s", "125", "-w", raw,
             "Hey Jarvis, delegate to Pocket Agent. Ask your client to check my phone and confirm the voice test. Please wait for the client result."],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )
        subprocess.run(
            ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
             "-i", raw, "-af", "apad=pad_dur=3", "-ar", "48000",
             "-ac", "1", "-c:a", "pcm_s16le", wav],
            check=True
        )
        with sync_playwright() as pw:
            browser = pw.chromium.launch(
                executable_path="/usr/bin/google-chrome",
                headless=True,
                args=["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu",
                      "--disable-extensions", "--renderer-process-limit=1",
                      "--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream",
                      "--use-file-for-fake-audio-capture=" + wav]
            )
            try:
                page = browser.new_page()
                page.goto(Path(page_path).as_uri())
                offer = page.evaluate("""async () => {
                  window.events = [];
                  window.delegations = 0; window.failures=[];
                  window.pc = new RTCPeerConnection({iceServers:[]});
                  window.pc.onconnectionstatechange = () => window.events.push("pc:" + window.pc.connectionState);
                  window.pc.ontrack = e => {
                    window.events.push("track:" + e.track.kind);
                    window.audio = new Audio();
                    window.audio.muted = true;
                    window.audio.srcObject = new MediaStream([e.track]);
                    window.audio.play().catch(() => {});
                  };
                  window.dc = window.pc.createDataChannel("oai-events");
                  window.dc.onmessage = e => {
                    try {
                      const m = JSON.parse(e.data);
                      window.events.push(m.type); if(m.type === "error") window.failures.push({code:m.error?.code || m.code || "unknown", message:String(m.error?.message || m.message || "").slice(0,110)});
                      if (m.type === "delegation.created" && m.item?.id) window.delegations++;
                    } catch(err) { window.events.push("invalid-event"); }
                  };
                  const stream = await navigator.mediaDevices.getUserMedia({audio:true});
                  for (const track of stream.getAudioTracks()) window.pc.addTrack(track, stream);
                  const offer = await window.pc.createOffer();
                  await window.pc.setLocalDescription(offer);
                  return window.pc.localDescription.sdp;
                }""")
                print("Local audio/WebRTC offer created (no Codex subprocess)", flush=True)
                answer, call_id = create_call(offer, authfile)
                exit_sideband = threading.Event()
                sideband_state = {"connected": False, "events": [], "error": ""}
                def sideband():
                    try:
                        token, account = credentials(authfile)
                        with websockets.sync.client.connect(
                            "wss://api.openai.com/v1/live/" + call_id,
                            additional_headers={
                                "Authorization": "Bearer " + token,
                                "chatgpt-account-id": account,
                                "originator": "codex_cli_rs",
                                "openai-alpha": "quicksilver=v2",
                            },
                            open_timeout=8, close_timeout=2,
                        ) as ws:
                            sideband_state["connected"] = True
                            for raw in ws:
                                if exit_sideband.is_set(): break
                                try: msg = json.loads(raw)
                                except (ValueError, TypeError): continue
                                sideband_state["events"].append(msg.get("type", "unknown"))
                                if msg.get("type") == "delegation.created":
                                    item = msg.get("item") or {}
                                    if item.get("id") and item.get("target") == "client":
                                        ws.send(json.dumps({
                                          "type": "delegation.context.append",
                                          "delegation_item_id": item["id"],
                                          "channel": "speakable",
                                          "content": [{"type": "input_text", "text": "Pocket Agent completed the voice test."}]
                                        }))
                                if len(sideband_state["events"]) >= 1000: break
                    except Exception as error:
                        sideband_state["error"] = type(error).__name__ + (":HTTP" + str(error.response.status_code) if getattr(error, "response", None) is not None else "")
                worker = threading.Thread(target=sideband,daemon=True)
                print("Subscription call returned valid SDP", {"audio_media": answer.count("m=audio"), "data_media": answer.count("m=application")}, flush=True)
                page.evaluate("s => window.pc.setRemoteDescription({type:'answer',sdp:s})", answer)
                try:
                    page.wait_for_function("window.pc.connectionState === 'connected'", timeout=10000)
                except Exception:
                    print("WebRTC did not connect, peer state:", page.evaluate("()=>window.pc.connectionState"), flush=True)
                # Existing-call attachments may return 404 until ICE is active.
                worker.start()
                page.wait_for_timeout(12000)
                exit_sideband.set()
                worker.join(timeout=2)
                print("Sideband:", {"connected": sideband_state["connected"],
                      "events": sorted(set(sideband_state["events"]))[:25],
                      "failure_type": sideband_state["error"]}, flush=True)
                stats = page.evaluate("""async () => {
                  const out={incoming:0,outgoing:0,delegations:window.delegations,channel:window.dc?.readyState,peer:window.pc?.connectionState,sctp:window.pc?.sctp?.state};
                  for(const s of (await window.pc.getStats()).values()) {
                    if (s.kind !== 'audio') continue;
                    if (s.type === 'inbound-rtp') out.incoming += s.packetsReceived || 0;
                    if (s.type === 'outbound-rtp') out.outgoing += s.packetsSent || 0;
                  }
                  return {...out, failures:window.failures, types:[...new Set(window.events)].slice(-22)};
                }""")
                print(json.dumps(stats), flush=True)
                return int(
                    stats["incoming"] > 0 and stats["outgoing"] > 0
                    and stats["delegations"] > 0
                    and sideband_state["connected"]
                    and "delegation.context.appended" in sideband_state["events"]
                    and not stats["failures"]
                )
            finally:
                try:
                    page.evaluate("() => { if(window.dc?.readyState==='open') window.dc.send(JSON.stringify({type:'session.close'})); window.pc?.close(); }")
                except Exception:
                    pass
                browser.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--auth-file", default=str(Path.home() / ".codex" / "auth.json"))
    args = parser.parse_args()
    try:
        result = run(args.auth_file)
    except Exception as error:
        print("Smoke failed:", str(error)[:220])
        raise SystemExit(1)
    if not result:
        raise SystemExit(2)
