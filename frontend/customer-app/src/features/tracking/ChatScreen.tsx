import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Alert, Box, IconButton, Stack, TextField, Typography } from '@mui/material';
import SendRoundedIcon from '@mui/icons-material/SendRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatDateTime } from '@lib/format';
import { brand } from '@lib/theme';
import { useChatChannel, useChatHistory } from './hooks';
import { useChatSocket } from './useChatSocket';
import type { ChatMessage } from './api';

/**
 * In-app Chat screen (Requirement 18). Loads the channel + history via TanStack
 * Query and connects a WebSocket for real-time delivery. Messages carry only
 * the sender's role and body — no personal phone numbers (Requirement 18.8).
 * Sending is disabled once the channel is deactivated (Requirement 18.5/18.6).
 *
 * The transcript scrolls inside the viewport while the composer stays pinned to
 * the bottom, so the keyboard never pushes the input off screen on mobile.
 */
export function ChatScreen() {
  const { bookingId = '' } = useParams();
  const channelQuery = useChatChannel(bookingId);
  const channelId = channelQuery.data?.channelId ?? '';
  const historyQuery = useChatHistory(channelId);
  const { state, send } = useChatSocket(channelId);

  const [draft, setDraft] = useState('');
  const listEndRef = useRef<HTMLDivElement | null>(null);

  const messages = historyQuery.data;

  // Keep the newest message in view as the conversation grows.
  useEffect(() => {
    listEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const channelActive = channelQuery.data?.active ?? false;
  const canSend = channelActive && state === 'open' && draft.trim().length > 0;

  const handleSend = () => {
    if (!canSend) return;
    send(draft);
    setDraft('');
  };

  return (
    <AppShell title="Message provider" hideBottomNav>
      <Box
        sx={{
          display: 'flex',
          flexDirection: 'column',
          // Fill the space the shell leaves below its app bar and padding.
          height: 'calc(100dvh - 60px - 40px)',
        }}
      >
        {channelQuery.data && !channelActive ? (
          <Alert severity="info" sx={{ mb: 1.5 }}>
            This chat is no longer active.
          </Alert>
        ) : null}

        <Box sx={{ flexGrow: 1, overflowY: 'auto', minHeight: 0, py: 1 }}>
          <QueryStateView
            isLoading={channelQuery.isLoading || historyQuery.isLoading}
            isError={channelQuery.isError || historyQuery.isError}
            error={channelQuery.error ?? historyQuery.error}
            onRetry={() => {
              void channelQuery.refetch();
              void historyQuery.refetch();
            }}
            isEmpty={!messages || messages.length === 0}
            emptyMessage="No messages yet. Say hello to coordinate your visit."
          >
            <Stack spacing={1.25}>
              {messages?.map((message) => (
                <MessageBubble key={message.id} message={message} />
              ))}
              <div ref={listEndRef} />
            </Stack>
          </QueryStateView>
        </Box>

        <Stack
          direction="row"
          spacing={1}
          alignItems="flex-end"
          sx={{ pt: 1.5, borderTop: 1, borderColor: 'divider' }}
        >
          <TextField
            fullWidth
            multiline
            maxRows={4}
            size="small"
            placeholder={channelActive ? 'Type a message…' : 'Chat is closed'}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                handleSend();
              }
            }}
            disabled={!channelActive}
            inputProps={{ 'aria-label': 'Message' }}
          />
          <IconButton
            onClick={handleSend}
            disabled={!canSend}
            aria-label="Send message"
            sx={{
              bgcolor: 'primary.main',
              color: 'primary.contrastText',
              width: 40,
              height: 40,
              '&:hover': { bgcolor: 'primary.dark' },
              '&.Mui-disabled': { bgcolor: 'action.disabledBackground' },
            }}
          >
            <SendRoundedIcon fontSize="small" />
          </IconButton>
        </Stack>
      </Box>
    </AppShell>
  );
}

function MessageBubble({ message }: { message: ChatMessage }) {
  const fromCustomer = message.senderRole === 'CUSTOMER';
  return (
    <Box sx={{ alignSelf: fromCustomer ? 'flex-end' : 'flex-start', maxWidth: '80%' }}>
      <Box
        sx={{
          px: 1.75,
          py: 1.25,
          bgcolor: fromCustomer ? 'primary.main' : '#FFFFFF',
          color: fromCustomer ? 'primary.contrastText' : 'text.primary',
          border: fromCustomer ? 'none' : `1px solid ${brand.line}`,
          // Square off the corner nearest the sender, the way native chat does.
          borderRadius: 2.5,
          borderBottomRightRadius: fromCustomer ? 4 : 20,
          borderBottomLeftRadius: fromCustomer ? 20 : 4,
        }}
      >
        <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
          {message.body}
        </Typography>
      </Box>
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ display: 'block', textAlign: fromCustomer ? 'right' : 'left', mt: 0.25, px: 0.5 }}
      >
        {formatDateTime(message.sentAt)}
      </Typography>
    </Box>
  );
}
