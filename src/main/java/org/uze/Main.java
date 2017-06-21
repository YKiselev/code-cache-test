package org.uze;

import com.google.common.base.Stopwatch;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import groovy.transform.CompileStatic;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Run with
 * -XX:ReservedCodeCacheSize=20m
 * -XX:InitialCodeCacheSize=20m
 */
public class Main {

    public static final String SCRIPT = "long result = count;\n" +
            "for (long i = 0; i < count; i++) {\n" +
            "    result += java.util.concurrent.ThreadLocalRandom.current().nextLong();\n" +
            "}\n" +
            "return result;";

    public static void main(String[] args) throws InterruptedException {
        MemoryPoolMXBean codeCache = null;
        for (MemoryPoolMXBean bean : ManagementFactory.getMemoryPoolMXBeans()) {
            if (bean.getName().equals("Code Cache")) {
                codeCache = bean;
            }
        }

        if (codeCache == null) {
            System.out.println("Code cache Mbean not found!");
            return;
        }

        final int threadCount = Runtime.getRuntime().availableProcessors();
        final ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        final List<Compiler> compilers = new ArrayList<>(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final Compiler compiler = new Compiler();
            compilers.add(compiler);
            executorService.submit(compiler);
        }

        System.out.println("Elapsed (s)\tCode Cache (MB)\tInvocations/s");
        final Stopwatch sw = Stopwatch.createStarted();
        final Stopwatch time = Stopwatch.createStarted();
        while (!Thread.currentThread().isInterrupted()) {
            if (sw.elapsed(TimeUnit.SECONDS) > 3) {
                sw.reset();

                float avgRate = 0;
                for (Compiler c : compilers) {
                    avgRate += c.getRate();
                }
                avgRate /= (float) threadCount;

                System.out.println(String.format("+%d\t%.2f\t%.2f",
                        time.elapsed(TimeUnit.SECONDS),
                        codeCache.getUsage().getUsed() / (1024.0 * 1024.0),
                        avgRate
                ));
                sw.start();
            }

            Thread.sleep(10);
        }

        for (Compiler c : compilers) {
            System.out.println(c.getResults());
        }
    }

    static class Compiler implements Runnable {

        private final GroovyClassLoader gcl;

        private volatile float rate;

        private volatile long results;

        public long getResults() {
            return results;
        }

        public float getRate() {
            return rate;
        }

        public Compiler() {
            final CompilerConfiguration cc = new CompilerConfiguration();

            cc.setScriptBaseClass(MyScript.class.getName());
            cc.addCompilationCustomizers(new ASTTransformationCustomizer(CompileStatic.class));

            gcl = new GroovyClassLoader(getClass().getClassLoader(), cc);
            gcl.setShouldRecompile(false);
        }

        public void run() {
            long totalTime = 0;
            long invocations = 0;
            int classInvocations = 0;
            results = 0;
            MyScript script = loadNewClassAndInstantiate();
            final Stopwatch sw = Stopwatch.createUnstarted();
            while (!Thread.currentThread().isInterrupted()) {
                sw.start();
                script.setCount(1_000);
                for (int i = 0; i < 10_000; i++) {
                    final long result = (long) script.run();
                    results += result;
                    invocations++;
                    classInvocations++;
                }
                sw.stop();
                totalTime = sw.elapsed(TimeUnit.MILLISECONDS);
                if (totalTime > 0) {
                    this.rate = invocations / (0.001f * totalTime);
                }
                if (classInvocations > 10_000) {
                    script = loadNewClassAndInstantiate();
                    classInvocations = 0;
                }
            }
        }

        private MyScript loadNewClassAndInstantiate() {
            try {
                final Class scriptClass = gcl.parseClass(SCRIPT);

                return (MyScript) scriptClass.newInstance();
            } catch (Throwable e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }

    public abstract static class MyScript extends Script {

        private long count;

        // used from script
        public long getCount() {
            return count;
        }

        public void setCount(long count) {
            this.count = count;
        }
    }
}
