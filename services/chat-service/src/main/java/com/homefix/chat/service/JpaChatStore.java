package com.homefix.chat.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.homefix.chat.domain.ChatChannel;
import com.homefix.chat.domain.ChatMessage;
import com.homefix.chat.repository.ChatChannelRepository;
import com.homefix.chat.repository.ChatMessageRepository;
import org.springframework.stereotype.Component;

/**
 * JPA-backed {@link ChatStore} delegating to the two Spring Data repositories.
 */
@Component
public class JpaChatStore implements ChatStore {

    private final ChatChannelRepository channelRepository;
    private final ChatMessageRepository messageRepository;

    public JpaChatStore(ChatChannelRepository channelRepository,
                        ChatMessageRepository messageRepository) {
        this.channelRepository = channelRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    public Optional<ChatChannel> findChannel(UUID bookingId) {
        return channelRepository.findById(bookingId);
    }

    @Override
    public ChatChannel saveChannel(ChatChannel channel) {
        return channelRepository.save(channel);
    }

    @Override
    public ChatMessage saveMessage(ChatMessage message) {
        return messageRepository.save(message);
    }

    @Override
    public List<ChatMessage> findMessages(UUID bookingId) {
        return messageRepository.findByBookingIdOrderBySentAtAsc(bookingId);
    }
}
