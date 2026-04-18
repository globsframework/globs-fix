package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.fields.BooleanField;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.model.MutableGlob;
import org.globsframework.fix.serializer.FixWriter;

public class FixWriterReplay implements FixWriter, FixMessageRepository {
    private final FixWriter writer;
    private int current;
    private boolean full;
    private FixRecoveredMessage[] saved; // write can be inverted versus the seqnum allocation.
    // so the order is not sequential.
    private final IntegerField headerSeqNum;
    private final BooleanField possDupFlag;
    private final StringField sendingTime;
    private final StringField originSendingTime;

    public FixWriterReplay(FixWriter writer, int maxSize,
                           IntegerField headerSeqNum,
                           BooleanField possDupFlag,
                           StringField sendingTime,
                           StringField originSendingTime) {
        this.writer = writer;
        saved = new FixRecoveredMessage[maxSize];
        this.headerSeqNum = headerSeqNum;
        this.possDupFlag = possDupFlag;
        this.sendingTime = sendingTime;
        this.originSendingTime = originSendingTime;
        current = -1;
    }

    @Override
    public void write(MutableGlob header, Glob message, MutableGlob trailer, boolean resetSeqNum) {
        writer.write(header, message, trailer, false);
        if (header.isTrue(possDupFlag)) {
            return;
        }
        final FixRecoveredMessage fixRecoveredMessage = new FixRecoveredMessage(header, message, trailer);
        synchronized (this) {
            current++;
            if (current >= saved.length) {
                current = 0;
                full = true;
            }
            saved[current] = fixRecoveredMessage;
        }
    }

    @Override
    public FixRecoveredMessage[] get(int fromSeqNum, int toSeqNum) {
        final int initialCapacity;
        if (toSeqNum == 0) {
            initialCapacity = saved.length;
            toSeqNum = Integer.MAX_VALUE;
        } else {
            initialCapacity = toSeqNum - fromSeqNum + 1;
        }
        if (initialCapacity > saved.length) {
            return null;
        }
        FixRecoveredMessage[] result = new FixRecoveredMessage[initialCapacity];
        synchronized (this) {
            for (int i = 0; i < (full ? saved.length : current); i++) {
                FixRecoveredMessage dd = saved[i];
                final int seq = dd.header().get(headerSeqNum);
                if (seq >= fromSeqNum && seq <= toSeqNum) {
                    result[seq - fromSeqNum] = new FixRecoveredMessage(
                            dd.header()
                                    .duplicate()
                                    .set(possDupFlag, true)
                                    .set(originSendingTime, dd.header().get(sendingTime))
                            , dd.message(), dd.trailer()
                    );
                }
            }
        }
        for (FixRecoveredMessage d : result) {
            if (d == null) {
                return null;
            }
        }
        return result;
    }
}
