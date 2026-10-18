package com.tiangong.task.dispatch;

/** 任务派发契约：local=进程内线程池，docker=RabbitMQ（同一契约，业务代码无感知）。 */
public interface TaskDispatcher {

    void dispatch(String taskNo);
}
