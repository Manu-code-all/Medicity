import { useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError } from "../api/client";
import { video } from "../api/endpoints";
import { useAuth } from "../auth/context";

type Phase = "starting" | "waiting" | "connecting" | "connected" | "left" | "ended";

/** The API's WebSocket origin: the same host as its HTTP one. */
function socketBase(): string {
  const http = import.meta.env.VITE_API_BASE_URL || window.location.origin;
  return http.replace(/^http/, "ws");
}

/**
 * A video visit, browser to browser. The server only introduces the two
 * sides: each browser sends its offer or answer and its network candidates
 * through the signalling socket, then the call itself (audio, video) goes
 * directly between them, encrypted. Whoever was in the room first makes the
 * offer when the other arrives, so there is never a clash of two offers.
 */
export function VideoRoomPage() {
  const { appointmentId = "" } = useParams();
  const { session } = useAuth();
  const local = useRef<HTMLVideoElement>(null);
  const remote = useRef<HTMLVideoElement>(null);
  const hangUp = useRef<() => void>(() => undefined);
  const [phase, setPhase] = useState<Phase>("starting");
  const [error, setError] = useState<string | null>(null);
  const [muted, setMuted] = useState(false);
  const [cameraOff, setCameraOff] = useState(false);
  const stream = useRef<MediaStream | null>(null);

  useEffect(() => {
    let closed = false;
    let socket: WebSocket | null = null;
    let peer: RTCPeerConnection | null = null;
    // Candidates can arrive before the description they belong to; they wait here.
    let early: RTCIceCandidateInit[] = [];
    let iceServers: string[] = [];

    const send = (message: object) => {
      if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
    };

    const newPeer = () => {
      peer?.close();
      early = [];
      const pc = new RTCPeerConnection({ iceServers: [{ urls: iceServers }] });
      stream.current?.getTracks().forEach((t) => pc.addTrack(t, stream.current!));
      pc.onicecandidate = (e) => e.candidate && send({ type: "candidate", candidate: e.candidate.toJSON() });
      pc.ontrack = (e) => {
        if (remote.current) remote.current.srcObject = e.streams[0] ?? null;
        setPhase("connected");
      };
      pc.onconnectionstatechange = () => {
        if (pc.connectionState === "failed") {
          setError("The call could not connect. One of the networks may block direct calls; try another network.");
        }
      };
      peer = pc;
      return pc;
    };

    const flushEarly = async () => {
      for (const c of early) await peer?.addIceCandidate(c).catch(() => undefined);
      early = [];
    };

    (async () => {
      try {
        const ticket = await video.ticket(appointmentId);
        iceServers = ticket.iceServers;
        if (!navigator.mediaDevices?.getUserMedia) {
          throw new Error("This browser cannot use a camera here. Open the visit in Chrome, Edge, Firefox or Safari.");
        }
        stream.current = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
        if (closed) return;
        if (local.current) local.current.srcObject = stream.current;
        newPeer();

        socket = new WebSocket(`${socketBase()}/ws/video?ticket=${encodeURIComponent(ticket.ticket)}`);
        socket.onmessage = async (event) => {
          const m = JSON.parse(event.data as string);
          if (m.type === "joined") {
            setPhase(m.peerPresent ? "connecting" : "waiting");
          } else if (m.type === "peer-joined") {
            // We were here first: we offer.
            const pc = newPeer();
            setPhase("connecting");
            await pc.setLocalDescription(await pc.createOffer());
            send({ type: "offer", sdp: pc.localDescription });
          } else if (m.type === "offer") {
            const pc = peer?.signalingState === "stable" && !peer.remoteDescription ? peer : newPeer();
            await pc.setRemoteDescription(m.sdp);
            await flushEarly();
            await pc.setLocalDescription(await pc.createAnswer());
            send({ type: "answer", sdp: pc.localDescription });
          } else if (m.type === "answer") {
            await peer?.setRemoteDescription(m.sdp);
            await flushEarly();
          } else if (m.type === "candidate") {
            if (peer?.remoteDescription) await peer.addIceCandidate(m.candidate).catch(() => undefined);
            else early.push(m.candidate);
          } else if (m.type === "peer-left" || m.type === "hangup") {
            if (remote.current) remote.current.srcObject = null;
            newPeer();
            setPhase("left");
          }
        };
        socket.onclose = (event) => {
          if (closed) return;
          if (event.code === 1008) setError("You joined this visit from another window.");
          else setPhase((p) => (p === "ended" ? p : "ended"));
        };
      } catch (e) {
        if (closed) return;
        if (e instanceof ApiError) setError(e.message);
        else if (e instanceof DOMException && e.name === "NotAllowedError") {
          setError("Allow the camera and microphone for this site to join the visit.");
        } else setError(e instanceof Error ? e.message : "Could not start the video visit.");
      }
    })();

    hangUp.current = () => {
      send({ type: "hangup" });
      closed = true;
      socket?.close();
      peer?.close();
      stream.current?.getTracks().forEach((t) => t.stop());
      setPhase("ended");
    };
    return () => {
      closed = true;
      socket?.close();
      peer?.close();
      stream.current?.getTracks().forEach((t) => t.stop());
    };
  }, [appointmentId]);

  const back = session?.role === "DOCTOR" ? `/doctor/visits/${appointmentId}` : "/portal/visits";
  const status: Record<Phase, string> = {
    starting: "Starting your camera…",
    waiting: session?.role === "DOCTOR" ? "Waiting for the patient to join…" : "Waiting for the doctor to join…",
    connecting: "Connecting…",
    connected: "Connected",
    left: "The other side left. They can rejoin from their visit.",
    ended: "The call has ended.",
  };

  return (
    <section className="video-room">
      <header className="section__head">
        <h1>Video visit</h1>
        <Link to={back}>Back to the visit</Link>
      </header>

      {error ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : (
        <p className="muted" role="status">
          {status[phase]}
        </p>
      )}

      <div className="video-room__stage">
        <video ref={remote} className="video-room__remote" autoPlay playsInline aria-label="The other side" />
        <video ref={local} className="video-room__self" autoPlay playsInline muted aria-label="You" />
      </div>

      {phase !== "ended" && !error && (
        <div className="video-room__controls">
          <button
            type="button"
            className="button--quiet"
            aria-pressed={muted}
            onClick={() => {
              stream.current?.getAudioTracks().forEach((t) => (t.enabled = muted));
              setMuted((m) => !m);
            }}
          >
            {muted ? "Unmute" : "Mute"}
          </button>
          <button
            type="button"
            className="button--quiet"
            aria-pressed={cameraOff}
            onClick={() => {
              stream.current?.getVideoTracks().forEach((t) => (t.enabled = cameraOff));
              setCameraOff((c) => !c);
            }}
          >
            {cameraOff ? "Camera on" : "Camera off"}
          </button>
          <button type="button" className="video-room__hangup" onClick={() => hangUp.current()}>
            Leave the call
          </button>
        </div>
      )}
      <p className="muted small">
        The call goes directly between your browser and the other side's, encrypted. Medicity does not record it.
      </p>
    </section>
  );
}
