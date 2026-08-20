package eiruna.structure.spawn.point;

public class DelayedTask {
    private final Runnable task;
    private final long executeAtMs;

    public DelayedTask(int delayTicks, Runnable task){
        long delayMs = (long)(delayTicks / 20.0f * 1000);
        this.executeAtMs = System.currentTimeMillis() + delayMs;
        this.task = task;
    }

    public boolean isReady() {
        return System.currentTimeMillis() >= executeAtMs;
    }

    public void execute() {
        task.run();
    }
}
