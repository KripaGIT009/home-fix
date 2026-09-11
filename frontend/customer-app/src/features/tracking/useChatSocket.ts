import { useCallback, useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useAuthStore } from '@stores/authStore';
import { buildWsUrl } from '@lib/realtime';
import { trackingKeys } from './hooks';
import type { ChatMessage } from './api';

export type ChatConnectionState = 'connecting' | 'open' | 'closed';

/** First reconnect delay; doubles per consecutive failure. */
const RECONNECT_BASE_DELAY_MS = 1_000;
/** Ceiling for the backoff, so a long outage retries once every 30 s. */
const RECONNECT_MAX_DELAY_MS = 30_000;
/** WebSocket close code for a deliberate, normal closure by either peer. */
const NORMAL_CLOSURE = 1000;

/**
 * WebSocket bridge for the in-app chat (Requirement 18). Connects to the Chat
 * Service channel, appends inbound messages to the TanStack Query history cache
 * so the message list stays a single source of truth, and exposes a `send`
 * function. Personal phone numbers are never part of the payload (Requirement
 * 18.8) — messages carry only the sender's role and body.
 *
 * A dropped connection reconnects on its own with exponential backoff: unlike
 * EventSource, WebSocket has no built-in retry, so without this a momentary
 * network blip left the chat closed for the rest of the screen's life.
 *
 * The channel is closed by the server once the Booking reaches
 * PAYMENT_COMPLETED/CANCELLED (Requirement 18.5). That arrives as a normal
 * closure and is deliberately *not* retried — the channel is gone for good.
 */
export function useChatSocket(channelId: string): {
  state: ChatConnectionState;
  send: (body: string) => void;
} {
  const queryClient = useQueryClient();
  const accessToken = useAuthStore((state) => state.accessToken);
  const [state, setState] = useState<ChatConnectionState>('connecting');
  const socketRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    if (!channelId) return;
    if (typeof WebSocket === 'undefined') return;

    // Effect-local so cleanup can stop a pending retry, and so a re-subscribe
    // (new channel or token) always starts from a zeroed backoff.
    let disposed = false;
    let attempt = 0;
    let retryTimer: number | undefined;

    function scheduleReconnect() {
      const delay = Math.min(RECONNECT_BASE_DELAY_MS * 2 ** attempt, RECONNECT_MAX_DELAY_MS);
      attempt += 1;
      retryTimer = window.setTimeout(connect, delay);
    }

    function connect() {
      retryTimer = undefined;
      const socket = new WebSocket(buildWsUrl(`/chat/channels/${channelId}/ws`, accessToken));
      socketRef.current = socket;
      setState('connecting');

      socket.onopen = () => {
        attempt = 0;
        setState('open');
      };

      socket.onmessage = (event: MessageEvent<string>) => {
        try {
          const message = JSON.parse(event.data) as ChatMessage;
          queryClient.setQueryData<ChatMessage[]>(
            trackingKeys.chatHistory(channelId),
            (prev = []) =>
              prev.some((existing) => existing.id === message.id) ? prev : [...prev, message],
          );
        } catch {
          // Ignore malformed frames.
        }
      };

      socket.onclose = (event: CloseEvent) => {
        socketRef.current = null;
        if (disposed) return;
        setState('closed');
        if (event.code !== NORMAL_CLOSURE) {
          scheduleReconnect();
        }
      };

      // An error is always followed by a close event, which drives the retry.
      socket.onerror = () => setState('closed');
    }

    connect();

    return () => {
      disposed = true;
      if (retryTimer !== undefined) {
        window.clearTimeout(retryTimer);
      }
      const socket = socketRef.current;
      socketRef.current = null;
      if (socket) {
        // Detach first: this close must not be mistaken for a dropped link.
        socket.onclose = null;
        socket.onerror = null;
        socket.close();
      }
    };
  }, [channelId, accessToken, queryClient]);

  const send = useCallback((body: string) => {
    const trimmed = body.trim();
    const socket = socketRef.current;
    if (!trimmed || !socket || socket.readyState !== WebSocket.OPEN) return;
    socket.send(JSON.stringify({ body: trimmed }));
  }, []);

  return { state, send };
}
