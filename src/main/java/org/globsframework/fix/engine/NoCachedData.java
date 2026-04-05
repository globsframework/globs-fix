package org.globsframework.fix.engine;

public class NoCachedData implements CachedData {
    public static final NoCachedData INSTANCE = new NoCachedData();
    @Override
    public Data[] get(int fromSeqNum, int toSeqNum) {
        return null;
    }
}
