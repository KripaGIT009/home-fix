package com.homefix.booking.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.booking.config.BookingProperties;
import com.homefix.booking.domain.JobMedia;
import com.homefix.booking.domain.JobMediaRepository;
import com.homefix.booking.media.MediaFile;
import com.homefix.booking.media.MediaStoragePort;

/**
 * Validates and stores booking media (Requirement 7.2): up to 10 files per booking, each no
 * larger than 50 MB and in JPEG, PNG, MP4, or MOV format. Storage is delegated to the
 * {@link MediaStoragePort} (S3 in production).
 */
@Service
public class MediaService {

    private final MediaStoragePort storage;
    private final JobMediaRepository mediaRepository;
    private final BookingProperties properties;

    public MediaService(MediaStoragePort storage,
                        JobMediaRepository mediaRepository,
                        BookingProperties properties) {
        this.storage = storage;
        this.mediaRepository = mediaRepository;
        this.properties = properties;
    }

    /**
     * Validates the batch as a whole (count limit is per booking, counting already-stored
     * media) and each file (size + content type), then stores each and persists a
     * {@code job_media} row.
     *
     * @throws BookingException if any constraint is violated (Requirement 7.2)
     */
    @Transactional
    public List<JobMedia> attach(UUID bookingId, String type, List<MediaFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        validateBatch(bookingId, files);

        List<JobMedia> stored = new ArrayList<>(files.size());
        for (MediaFile file : files) {
            String key = storage.store(bookingId, file);
            JobMedia media = JobMedia.of(bookingId, type, file.contentType(), file.sizeBytes(), key);
            stored.add(mediaRepository.save(media));
        }
        return stored;
    }

    private void validateBatch(UUID bookingId, List<MediaFile> files) {
        BookingProperties.Media cfg = properties.getMedia();
        long existing = mediaRepository.countByBookingId(bookingId);
        if (existing + files.size() > cfg.getMaxFiles()) {
            throw BookingException.media(
                    "A booking may have at most " + cfg.getMaxFiles() + " media files");
        }
        for (MediaFile file : files) {
            validateFile(file, cfg);
        }
    }

    private void validateFile(MediaFile file, BookingProperties.Media cfg) {
        if (file.contentType() == null || !cfg.getAllowedContentTypes().contains(file.contentType())) {
            throw BookingException.media(
                    "Unsupported media type '" + (file == null ? null : file.contentType())
                            + "'; allowed: " + cfg.getAllowedContentTypes());
        }
        if (file.sizeBytes() <= 0) {
            throw BookingException.media("Media file is empty");
        }
        if (file.sizeBytes() > cfg.getMaxFileSize()) {
            throw BookingException.media(
                    "Media file exceeds the maximum size of " + cfg.getMaxFileSize() + " bytes");
        }
    }
}
