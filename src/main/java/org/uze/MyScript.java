package org.uze;

import groovy.lang.Script;

/**
 * @author Yuriy Kiselev (uze@yandex.ru).
 */
public abstract class MyScript extends Script {

    private long count;

    // used from script
    public long getCount() {
        return count;
    }

    void setCount(long count) {
        this.count = count;
    }
}
