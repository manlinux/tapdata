package com.tapdata.tm.schedule;


import com.tapdata.tm.commons.task.dto.TaskDto;
import com.tapdata.tm.commons.task.dto.TaskResetEventDto;
import com.tapdata.tm.config.security.UserDetail;
import com.tapdata.tm.task.service.TaskResetLogService;
import com.tapdata.tm.task.service.TaskService;
import com.tapdata.tm.user.service.UserService;
import com.tapdata.tm.utils.MongoUtils;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class TaskResetSchedule {

    @Autowired
    private TaskResetLogService taskResetLogService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private UserService userService;


    @Scheduled(fixedDelay = 30 * 1000)
    @SchedulerLock(name ="checkTaskReset", lockAtMostFor = "15s", lockAtLeastFor = "15s")
    public void checkTaskReset() {
        checkNoResponseOp();
        resetRetry();
        cleanTaskResetTimes();
    }


    public void checkNoResponseOp() {

        try {
            Criteria taskCri = Criteria.where("status").in(TaskDto.STATUS_RENEWING, TaskDto.STATUS_DELETING);

            Query query1 = new Query(taskCri);
            query1.fields().include("status", "last_updated");
            List<TaskDto> taskDtos = taskService.findAll(query1);
            List<String> taskIds = taskDtos.stream().map(t -> t.getId().toHexString()).distinct().collect(Collectors.toList());
            Criteria criteria1 = Criteria.where("taskId").in(taskIds);
            Query query2 = new Query(criteria1);
            List<TaskResetEventDto> taskResetEventDtos = taskResetLogService.find(query2);
            Map<String, List<TaskResetEventDto>> taskLogMap = taskResetEventDtos.stream()
                    .collect(Collectors.groupingBy(TaskResetEventDto::getTaskId));


            List<TaskDto> updateTask = new ArrayList<>();
            long overTime = 50 * 1000;
            long curr = System.currentTimeMillis();
            for (TaskDto taskDto : taskDtos) {
                List<TaskResetEventDto> taskResetEvents = taskLogMap.get(taskDto.getId().toHexString());
                if (CollectionUtils.isNotEmpty(taskResetEvents)) {
                    List<TaskResetEventDto.ResetStatusEnum> enums = taskResetEvents.stream().map(TaskResetEventDto::getStatus)
                            .distinct().collect(Collectors.toList());
                    if (enums.contains(TaskResetEventDto.ResetStatusEnum.TASK_SUCCEED)) {

                        continue;
                    } else if (enums.contains(TaskResetEventDto.ResetStatusEnum.TASK_FAILED)) {
                        updateTask.add(taskDto);
                        continue;
                    }

                    TaskResetEventDto taskResetEventDto = taskResetEvents.stream().max(Comparator.comparing(TaskResetEventDto::getTime)).get();
                    if (curr - taskResetEventDto.getTime().getTime() >= overTime) {
                        updateTask.add(taskDto);
                    }

                } else {
                    if (curr - taskDto.getLastUpdAt().getTime() >= overTime) {
                        updateTask.add(taskDto);
                    }
                }

            }


            for (TaskDto taskDto : updateTask) {
                try {
                    if (TaskDto.STATUS_RENEWING.equals(taskDto.getStatus())) {
                        taskService.updateStatus(taskDto.getId(), TaskDto.STATUS_RENEW_FAILED);
                    } else if (TaskDto.STATUS_DELETING.equals(taskDto.getStatus())) {
                        taskService.updateStatus(taskDto.getId(), TaskDto.STATUS_DELETE_FAILED);
                    } else {
                        taskResetLogService.clearLogByTaskId(taskDto.getId().toHexString());
                    }
                } catch (Exception e) {
                    log.info("check reset no response, task id = {}", taskDto.getId().toHexString());
                }
            }


        } catch (Exception e) {
            log.warn("check task reset no response error");
        }
    }

    public void resetRetry() {
        try {
            //查询重置删除失败的任务，并且重试次数少于3次
            Criteria criteria = Criteria.where("status").in(TaskDto.STATUS_RENEW_FAILED, TaskDto.STATUS_DELETE_FAILED).and("is_deleted").ne(true)
                    .orOperator(Criteria.where("resetTimes").exists(false), Criteria.where("resetTimes").lt(2));
            Query query = new Query(criteria);
            List<TaskDto> taskDtos = taskService.findAll(query);
            if (CollectionUtils.isEmpty(taskDtos)) {
                return;
            }
            for (TaskDto taskDto : taskDtos) {
                try {
                    UserDetail user = userService.loadUserById(MongoUtils.toObjectId(taskDto.getUserId()));
                    int resetTimes = taskDto.getResetTimes() == null ? 0 : taskDto.getResetTimes();
                    Update update = Update.update("resetTimes", resetTimes + 1);
                    Query queryTask = new Query(Criteria.where("_id").is(taskDto.getId()));
                    taskService.update(queryTask, update);


                    if (TaskDto.STATUS_RENEW_FAILED.equals(taskDto.getStatus())) {
                        taskService.renew(taskDto.getId(), user);
                    } else if (TaskDto.STATUS_DELETE_FAILED.equals(taskDto.getStatus())) {
                        taskService.remove(taskDto.getId(), user);
                    }
                } catch (Exception e) {
                    log.info("reset retry error, task = {}", taskDto.getName());
                }

            }
        } catch (Exception e) {
            log.warn("all reset retry error");
        }
    }

    public void cleanTaskResetTimes() {
        try {
            Criteria taskCriteria = Criteria.where("status").nin(TaskDto.STATUS_RENEW_FAILED, TaskDto.STATUS_DELETE_FAILED, TaskDto.STATUS_RENEWING, TaskDto.STATUS_DELETING)
                    .and("is_deleted").ne(true).and("resetTimes").gt(0);
            Query query = new Query(taskCriteria);
            query.fields().include("_id", "name");
            List<TaskDto> taskDtos = taskService.findAll(query);
            for (TaskDto taskDto : taskDtos) {
                try {
                    Update update = Update.update("resetTimes", 0);
                    Query queryTask = new Query(Criteria.where("_id").is(taskDto.getId()));
                    taskService.update(queryTask, update);
                } catch (Exception e) {
                    log.info("clean task reset times error, task = {}", taskDto.getName());
                }
            }
        } catch (Exception e) {
            log.warn("clean all task reset times error");
        }
    }

}
