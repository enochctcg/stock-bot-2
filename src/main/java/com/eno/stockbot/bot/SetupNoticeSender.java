package com.eno.stockbot.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.eno.stockbot.config.BotProperties;
import com.eno.stockbot.subscription.Subscriber;
import com.eno.stockbot.subscription.SubscriberRepository;
import com.eno.stockbot.telegram.Notifier;

/**
 * 推播改成每人自訂時段與間隔後，既有使用者需要重新設定一次才會恢復推播。
 * V2 migration 會把既有使用者標記為 setup_notice_pending，服務啟動完成後在這裡通知一次並清除標記，
 * 所以重啟服務不會重複通知。
 */
@Component
public class SetupNoticeSender {

    private static final Logger log = LoggerFactory.getLogger(SetupNoticeSender.class);

    static final String NOTICE = """
            📢 推播功能更新

            現在可以自訂推播時段與間隔了（最短每 1 分鐘）。
            原本的固定推播已停止，請重新設定一次，設定完成後就會恢復定時推播。""";

    private final SubscriberRepository subscribers;
    private final CommandHandler handler;
    private final Notifier notifier;
    private final BotProperties props;

    public SetupNoticeSender(SubscriberRepository subscribers, CommandHandler handler, Notifier notifier,
                             BotProperties props) {
        this.subscribers = subscribers;
        this.handler = handler;
        this.notifier = notifier;
        this.props = props;
    }

    /** 放到 virtual thread 執行，不拖慢啟動。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread.ofVirtual().name("setup-notice").start(this::sendPending);
    }

    void sendPending() {
        int sent = 0;
        for (Subscriber s : subscribers.findPendingSetupNotice()) {
            try {
                // 已暫停推播、不在白名單、或已經自己設定好的使用者不打擾
                if (s.enabled() && !s.configured() && props.isAllowed(s.chatId())) {
                    CommandHandler.View setup = handler.windowView(s.chatId());
                    notifier.send(s.chatId(), NOTICE + "\n\n" + setup.text(), setup.markup());
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("通知 {} 重新設定推播失敗: {}", s.chatId(), e.getMessage());
            } finally {
                // 不論成功與否都只通知一次；送不到（例如封鎖 bot）的使用者，之後用 /start 也會進入設定
                subscribers.clearSetupNotice(s.chatId());
            }
        }
        if (sent > 0) {
            log.info("已通知 {} 位既有使用者重新設定推播", sent);
        }
    }
}
