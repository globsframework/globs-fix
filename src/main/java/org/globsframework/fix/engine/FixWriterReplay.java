package org.globsframework.fix.engine;

import org.globsframework.core.metamodel.fields.BooleanField;
import org.globsframework.core.metamodel.fields.IntegerField;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.fix.serializer.FixWriter;

public class FixWriterReplay implements FixWriter, FixMessageRepository {
    private final FixWriter writer;
    private int current;
    private boolean full;
    private FixMessage[] saved; // write can be inverted versus the seqnum allocation.
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
        saved = new FixMessage[maxSize];
        this.headerSeqNum = headerSeqNum;
        this.possDupFlag = possDupFlag;
        this.sendingTime = sendingTime;
        this.originSendingTime = originSendingTime;
        current = -1;
    }

    @Override
    public void write(FixMessage fixMessage) {
        writer.write(fixMessage);
        if (fixMessage.getHeader().isTrue(possDupFlag)) {
            return;
        }
        synchronized (this) {
            current++;
            if (current >= saved.length) {
                current = 0;
                full = true;
            }
            saved[current] = fixMessage;
        }
    }

    @Override
    public FixMessage[] get(int fromSeqNum, int toSeqNum) {
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
        FixMessage[] result = new FixMessage[initialCapacity];
        synchronized (this) {
            for (int i = 0; i < (full ? saved.length : current); i++) {
                FixMessage dd = saved[i];
                final int seq = dd.getHeader().get(headerSeqNum);
                if (seq >= fromSeqNum && seq <= toSeqNum) {
                    result[seq - fromSeqNum] = FixMessageImpl.fromGlob(
                            dd.getHeader()
                                    .duplicate()
                                    .set(possDupFlag, true)
                                    .set(originSendingTime, dd.getHeader().get(sendingTime)),
                            dd.getBody(),
                            dd.getTrailer(),
                            false
                    );
                }
            }
        }
        for (FixMessage d : result) {
            if (d == null) {
                return null;
            }
        }
        return result;
    }
}
