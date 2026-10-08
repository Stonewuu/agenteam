package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Candidate;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Notice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Service
public class NotificationDeliveryService {
    private final NotificationDeliveryMapper notifications;
    private final Clock clock;
    private final NotificationWriteService writer;
    private final ChannelNotificationRouting channels;

    public NotificationDeliveryService(NotificationDeliveryMapper notifications, Clock clock, NotificationWriteService writer,
                                       ChannelNotificationRouting channels) {
        this.notifications = notifications;
        this.clock = clock;
        this.writer = writer;
        this.channels = channels;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String enterprise, String user, Notice notice) {
        enqueue(enterprise, user, notice, Duration.ZERO);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String enterprise, String user, Notice notice, Duration delay) {
        var now = clock.instant();
        notifications.enqueue(enterprise, user, notice, now, now.plus(delay));
    }

    public List<Candidate> candidates() {
        return notifications.candidates(clock.instant());
    }

    @Transactional
    public void deliver(Candidate candidate) {
        var recipient = writer.lockRecipient(candidate.enterprise(), candidate.user());
        var pending = notifications.lockPending(candidate);
        if (pending.isEmpty()) {
            return;
        }
        if (recipient.isPresent()) {
            var written = writer.writeLocked(recipient.get(), notifications.notice(pending.get()), null, null);
            if (written.created() && notifications.allowsExternalChannels(pending.get())) {
                channels.automatic(written.notification(), pending.get().getCreatedAt());
            }
        }
        notifications.finish(candidate, recipient.isPresent() ? "completed" : "cancelled", clock.instant());
    }

    public boolean completionEnabled(String user) {
        return notifications.completionEnabled(user);
    }
}
