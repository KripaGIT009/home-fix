import { useCallback, useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useAuthStore } from '@stores/authStore';
import { buildWsUrl } from '@lib/realtime';
import { trackingKeys } from './hooks';
import type { ChatMessage } from './api';

export type ChatConnectionState = 'connecting' | 'open' | 'closed';

/**
 * WebSocket bridge for the in-app chat (Requirement 18). Connects to the Chat
 * Service channel, appends inbound messages to the TanStack Query history cache
 * so the message list stays a single source of truth, and exposes a `send`
 * function. Personal phone numbers are never part of the payload (Requirement
 * 18.8) — messages carry only the sender's role and body.
 *
 * The channel is closed by the server once the Booking reaches
 * PAYMENT_COMPLETED/CANCELLED (Requirement 18.5); the socket simply closes.
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

    const url = buildWsUrl(`/chat/channels/${channelId}/ws`, accessToken);
    const socket = new WebSocket(url);
    socketRef.current = socket;
    setState('connecting');

    socket.onopen = () => setState('open');

    socket.onmessage = (event: MessageEvent<string>) => {
      try {
        const message = JSON.parse(event.data) as ChatMessage;
        queryClient.setQueryData<ChatMessage[]>(trackingKeys.chatHistory(channelId), (prev = []) =>
          prev.some((existing) => existing.id === message.id) ? prev : [...prev, message],
        );
      } catch {
        // Ignore malformed frames.
      }
    };

    socket.onclose = () => setState('closed');
    socket.onerror = () => setState('closed');

    return () => {
      socket.close();
      socketRef.current = null;
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
