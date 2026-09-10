package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homefix.booking.config.BookingProperties;
import com.homefix.booking.domain.JobMedia;
import com.homefix.booking.domain.JobMediaRepository;
import com.homefix.booking.media.MediaFile;
import com.homefix.booking.media.MediaStoragePort;

/**
 * Verifies media upload constraints (Requirement 7.2): up to 10 files, each &le; 50 MB, in
 * JPEG/PNG/MP4/MOV format.
 */
@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    private static final UUID BOOKING = UUID.randomUUID();

    @Mock
    private MediaStoragePort storage;
    @Mock
    private JobMediaRepository mediaRepository;

    private MediaService service;

    @BeforeEach
    void setUp() {
        service = new MediaService(storage, mediaRepository, new BookingProperties());
        lenient().when(storage.store(any(), any())).thenReturn("s3-key");
        lenient().when(mediaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static MediaFile file(String contentType, long size) {
        return new MediaFile("f", contentType, size, new byte[]{1});
    }

    @Test
    void storesValidFiles() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);
        List<JobMedia> stored = service.attach(BOOKING, "CUSTOMER_UPLOAD", List.of(
                file("image/jpeg", 1024),
                file("video/mp4", 1024)));
        assertThat(stored).hasSize(2);
        verify(storage, org.mockito.Mockito.times(2)).store(any(), any());
    }

    @Test
    void acceptsAllFourAllowedFormats() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);
        List<JobMedia> stored = service.attach(BOOKING, "CUSTOMER_UPLOAD", List.of(
                file("image/jpeg", 10),
                file("image/png", 10),
                file("video/mp4", 10),
                file("video/quicktime", 10)));
        assertThat(stored).hasSize(4);
    }

    @Test
    void rejectsUnsupportedFormat() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);
        assertThatThrownBy(() -> service.attach(BOOKING, "CUSTOMER_UPLOAD",
                List.of(file("application/pdf", 10))))
                .isInstanceOf(BookingException.class)
                .hasMessageContaining("Unsupported media type");
        verify(storage, never()).store(any(), any());
    }

    @Test
    void rejectsFileOverFiftyMegabytes() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);
        long overLimit = 52_428_800L + 1;
        assertThatThrownBy(() -> service.attach(BOOKING, "CUSTOMER_UPLOAD",
                List.of(file("image/png", overLimit))))
                .isInstanceOf(BookingException.class)
                .hasMessageContaining("maximum size");
        verify(storage, never()).store(any(), any());
    }

    @Test
    void acceptsFileExactlyAtFiftyMegabytes() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);
        List<JobMedia> stored = service.attach(BOOKING, "CUSTOMER_UPLOAD",
                List.of(file("image/png", 52_428_800L)));
        assertThat(stored).hasSize(1);
    }

    @Test
    void rejectsBatchExceedingTenFilesPerBooking() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(0L);
        List<MediaFile> eleven = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> file("image/jpeg", 10))
                .toList();
        assertThatThrownBy(() -> service.attach(BOOKING, "CUSTOMER_UPLOAD", eleven))
                .isInstanceOf(BookingException.class)
                .hasMessageContaining("at most 10");
        verify(storage, never()).store(any(), any());
    }

    @Test
    void rejectsWhenExistingPlusNewExceedsLimit() {
        when(mediaRepository.countByBookingId(BOOKING)).thenReturn(8L);
        List<MediaFile> three = List.of(
                file("image/jpeg", 10), file("image/jpeg", 10), file("image/jpeg", 10));
        assertThatThrownBy(() -> service.attach(BOOKING, "CUSTOMER_UPLOAD", three))
                .isInstanceOf(BookingException.class);
    }

    @Test
    void emptyBatchIsANoop() {
        assertThat(service.attach(BOOKING, "CUSTOMER_UPLOAD", List.of())).isEmpty();
        verify(storage, never()).store(any(), any());
    }
}
