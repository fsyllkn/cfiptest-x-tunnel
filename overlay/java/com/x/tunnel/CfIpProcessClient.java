package com.x.tunnel;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

final class CfIpProcessClient {
    interface Listener {
        void onProgress(String message);
        void onResult(String json);
        void onError(String message);
    }

    private final Context context;
    private final AtomicReference<Process> currentProcess = new AtomicReference<>();

    CfIpProcessClient(Context context) {
        this.context = context.getApplicationContext();
    }

    void scan(boolean v4, boolean tls, int bandwidth, int maxResults, Listener listener) {
        List<String> args = baseCommand("scan");
        args.add("-v4=" + v4);
        args.add("-tls=" + tls);
        args.add("-bandwidth=" + bandwidth);
        args.add("-max-results=" + maxResults);
        run(args, listener);
    }

    void update(Listener listener) {
        run(baseCommand("update"), listener);
    }

    void clearCache(Listener listener) {
        run(baseCommand("clear"), listener);
    }

    void cancel() {
        Process p = currentProcess.getAndSet(null);
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) {}
            try { p.destroyForcibly(); } catch (Throwable ignored) {}
        }
    }

    private List<String> baseCommand(String command) {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File exe = new File(nativeDir, "libcfipscan.so");
        File cache = new File(context.getFilesDir(), "cfip");
        if (!cache.exists()) cache.mkdirs();

        List<String> args = new ArrayList<>();
        args.add(exe.getAbsolutePath());
        args.add(command);
        args.add("-cache-dir=" + cache.getAbsolutePath());
        return args;
    }

    private void run(List<String> args, Listener listener) {
        Process process = null;
        boolean gotResult = false;
        try {
            File exe = new File(args.get(0));
            if (!exe.exists()) {
                listener.onError("CFIP 扫描程序不存在: " + exe.getAbsolutePath());
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            process = pb.start();
            currentProcess.set(process);

            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("PROGRESS	")) {
                        listener.onProgress(line.substring("PROGRESS	".length()));
                    } else if (line.startsWith("RESULT	")) {
                        gotResult = true;
                        listener.onResult(line.substring("RESULT	".length()));
                    }
                }
            }

            int exit = process.waitFor();
            if (!gotResult && exit != 0) {
                listener.onError("CFIP 进程退出，code=" + exit);
            } else if (!gotResult) {
                listener.onError("CFIP 未返回结果");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onError("操作已中断");
        } catch (Throwable t) {
            listener.onError(t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (process != null) currentProcess.compareAndSet(process, null);
        }
    }
}
