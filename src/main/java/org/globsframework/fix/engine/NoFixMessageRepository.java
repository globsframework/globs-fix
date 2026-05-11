package org.globsframework.fix.engine;

public class NoFixMessageRepository implements FixMessageRepository {
    public static final NoFixMessageRepository INSTANCE = new NoFixMessageRepository();
    @Override
    public FixMessage[] get(int fromSeqNum, int toSeqNum) {
        return null;
    }
}
