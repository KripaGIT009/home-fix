import { Button } from '@mui/material';
import ChatRoundedIcon from '@mui/icons-material/ChatRounded';
import { useChatChannel } from './hooks';

/**
 * In-app chat entry point shown on active booking screens (Requirement 18,
 * Task 33). Enabled only while the Booking's chat channel is active — the
 * channel is deactivated once the Booking reaches PAYMENT_COMPLETED/CANCELLED
 * (Requirement 18.5). Tapping navigates to the Chat screen (WebSocket).
 */
export function ChatButton({ bookingId, onOpen }: { bookingId: string; onOpen: () => void }) {
  const channel = useChatChannel(bookingId);
  const disabled = channel.isLoading || (channel.data ? !channel.data.active : channel.isError);

  return (
    <Button
      variant="outlined"
      size="large"
      fullWidth
      startIcon={<ChatRoundedIcon />}
      onClick={onOpen}
      disabled={disabled}
      aria-label="Open chat with your provider"
    >
      {channel.data && !channel.data.active ? 'Chat closed' : 'Message provider'}
    </Button>
  );
}
