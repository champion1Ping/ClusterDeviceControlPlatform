package cc.bitky.clusterdeviceplatform.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import cc.bitky.clusterdeviceplatform.messageutils.config.ChargeStatus;
import cc.bitky.clusterdeviceplatform.messageutils.config.WorkStatus;
import cc.bitky.clusterdeviceplatform.messageutils.msg.statusreply.MsgReplyDeviceStatus;
import cc.bitky.clusterdeviceplatform.server.config.ServerSetting;
import cc.bitky.clusterdeviceplatform.server.db.bean.CardSet;
import cc.bitky.clusterdeviceplatform.server.db.bean.Device;
import cc.bitky.clusterdeviceplatform.server.db.operate.CardSetOperate;
import cc.bitky.clusterdeviceplatform.server.db.operate.DbRoutineOperate;
import cc.bitky.clusterdeviceplatform.server.db.operate.DeviceOperate;
import cc.bitky.clusterdeviceplatform.server.db.operate.EmployeeOperate;
import cc.bitky.clusterdeviceplatform.server.db.statistic.repo.ProcessedMsgRepo;
import cc.bitky.clusterdeviceplatform.server.db.work.bean.StatusItem;
import cc.bitky.clusterdeviceplatform.server.server.repo.DeviceStatusRepository;
import cc.bitky.clusterdeviceplatform.server.server.repo.TcpFeedBackRepository;
import cc.bitky.clusterdeviceplatform.server.tcp.statistic.except.TcpFeedbackItem;
import cc.bitky.clusterdeviceplatform.server.web.client.bean.CardType;
import reactor.core.publisher.Mono;

@Service
public class DbPresenter {

    private final CardSetOperate cardSetOperate;
    private final DeviceOperate deviceOperate;
    private final EmployeeOperate employeeOperate;
    private final DeviceStatusRepository deviceStatusRepository;
    private final DbRoutineOperate dbRoutineOperate;
    private final TcpFeedBackRepository feedBackRepository;
    private Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public DbPresenter(CardSetOperate cardSetOperate, DeviceOperate deviceOperate, EmployeeOperate employeeOperate, DeviceStatusRepository deviceStatusRepository, DbRoutineOperate dbRoutineOperate, TcpFeedBackRepository feedBackRepository) {
        this.cardSetOperate = cardSetOperate;
        this.deviceOperate = deviceOperate;
        this.employeeOperate = employeeOperate;
        this.deviceStatusRepository = deviceStatusRepository;
        this.dbRoutineOperate = dbRoutineOperate;
        this.feedBackRepository = feedBackRepository;
    }

    public EmployeeOperate getEmployeeOperate() {
        return employeeOperate;
    }

    /**
     * 保存特定的卡号组
     *
     * @param cards 特定的卡号组
     * @param type  卡号组的类型
     * @return 保存成功
     */
    public Mono<CardSet> saveCardSet(String[] cards, CardType type) {
        return cardSetOperate.saveCardSet(type.name(), cards);
    }

    /**
     * 根据类型获取指定的卡号组
     *
     * @param type 卡号组的类型
     * @return 特定的卡号组
     */
    public Optional<CardSet> queryCardSet(CardType type) {
        return Optional.ofNullable(cardSetOperate.obtainCardSet(type.name()).block());
    }

    /**
     * 获取设备的集合
     *
     * @param groupId  设备组 Id
     * @param deviceId 设备 Id
     * @return 设备的集合
     */
    public List<Device> queryDeviceInfo(int groupId, int deviceId) {
        return deviceOperate.queryDeviceInfo(groupId, deviceId);
    }

    /**
     * 保存特定的设备
     *
     * @param device 特定的设备
     * @return 特定的设备
     */
    public Device saveDeviceInfo(Device device) {
        return deviceOperate.saveDeviceInfo(device);
    }

    /**
     * 从服务器缓存中获取设备状态
     *
     * @param groupId  设备组 ID
     * @param deviceId 设备 ID
     * @param type     状态类型
     * @return 状态 Item
     */
    public StatusItem obtainStatusByCache(int groupId, int deviceId, MsgReplyDeviceStatus.Type type) {
        return deviceStatusRepository.getStatus(groupId, deviceId, type);
    }

    /**
     * 获取设备组最近通信时刻的时间戳
     *
     * @param groupId 设备组 ID
     * @return 特定的时间戳
     */
    public long getDeviceGroupRecentCommTime(int groupId) {
        return deviceStatusRepository.getDeviceGroupRecentCommTime(groupId);
    }


    /**
     * 总帧数统计
     *
     * @param msgStatus 状态回复消息
     */
    private void statisticUpdateMsgCount(MsgReplyDeviceStatus msgStatus, MessageType type) {
        switch (type) {
            case all:
                if (msgStatus.getType() == MsgReplyDeviceStatus.Type.CHARGE) {
                    ProcessedMsgRepo.MSG_CHARGE_COUNT.incrementAndGet();
                } else {
                    ProcessedMsgRepo.MSG_WORK_COUNT.incrementAndGet();
                }
                break;
            case fixed:
                if (msgStatus.getType() == MsgReplyDeviceStatus.Type.CHARGE) {
                    ProcessedMsgRepo.MSG_CHARGE_COUNT_FIXED.incrementAndGet();
                } else {
                    ProcessedMsgRepo.MSG_WORK_COUNT_FIXED.incrementAndGet();
                }
                break;
            case variable:
                if (msgStatus.getType() == MsgReplyDeviceStatus.Type.CHARGE) {
                    ProcessedMsgRepo.MSG_CHARGE_COUNT_VARIABLE.incrementAndGet();
                } else {
                    ProcessedMsgRepo.MSG_WORK_COUNT_VARIABLE.incrementAndGet();
                }
                break;
            default:
        }
    }

    /**
     * 处理设备状态包
     *
     * @param msgStatus 设备状态包
     * @return 处理后的 Device。 null: 未找到指定的 Device 或 Device 无更新
     */
    public Device handleMsgDeviceStatus(MsgReplyDeviceStatus msgStatus) {
        // 帧数统计
        statisticUpdateMsgCount(msgStatus, MessageType.all);
        long l1 = System.currentTimeMillis();
        //比较服务器缓存，是否状态更新，未更新直接 return
        StatusItem status = obtainStatusByCache(msgStatus.getGroupId(), msgStatus.getDeviceId(), msgStatus.getType());
        if (status.getStatus() == msgStatus.getStatus()) {
            if (ServerSetting.DEBUG) {
                logger.info("设备「" + msgStatus.getGroupId() + ", " + msgStatus.getDeviceId() + "」『"
                        + status.getStatus() + "->" + msgStatus.getStatus() + "』: " + msgStatus.getType().getDetail() + "无更新");
                statisticUpdateMsgCount(msgStatus, MessageType.fixed);
            }
            return null;
        } else if (status.getTime() > msgStatus.getTime()) {
            if (ServerSetting.DEBUG) {
                Instant cacheInstant = Instant.ofEpochMilli(status.getTime());
                Instant msgInstant = Instant.ofEpochMilli(msgStatus.getTime());
                logger.info("已缓存时间:" + LocalDateTime.ofInstant(cacheInstant, ZoneId.systemDefault()).toString());
                logger.info("消息时间:" + LocalDateTime.ofInstant(msgInstant, ZoneId.systemDefault()).toString());
                logger.info("设备「" + msgStatus.getGroupId() + ", " + msgStatus.getDeviceId() + "」『"
                        + status.getStatus() + "->" + msgStatus.getStatus() + "』: " + msgStatus.getType().getDetail() + "已过期");
                statisticUpdateMsgCount(msgStatus, MessageType.fixed);
            }
            return null;
        } else {
            // 设备状态可能已改变时，服务器缓存状态被更新
            deviceStatusRepository.setStatus(msgStatus.getGroupId(), msgStatus.getDeviceId(), StatusItem.newInstance(msgStatus), msgStatus.getType());
            // 设备状态可能已改变时，更新统计信息
            if (msgStatus.getType() == MsgReplyDeviceStatus.Type.WORK && msgStatus.getStatus() != WorkStatus.NORMAL) {
                feedBackRepository.putItem(TcpFeedbackItem.createDeviceWorkException(msgStatus));
            } else if (msgStatus.getType() == MsgReplyDeviceStatus.Type.WORK && msgStatus.getStatus() == WorkStatus.NORMAL) {
                feedBackRepository.removeItem(TcpFeedbackItem.createDeviceWorkException(msgStatus));
            }
        }

        // 设备状态可能已改变时，将状态更新至数据库，如若状态未更新则不提交至数据库
        Device device;
        if (msgStatus.getType() == MsgReplyDeviceStatus.Type.CHARGE) {
            device = deviceOperate.handleChargeStatus(msgStatus);
        } else {
            device = deviceOperate.handleWorkStatus(msgStatus);
        }
        if (device == null || device.getChargeStatus() == ChargeStatus.FRAME_EXCEPTION) {
            statisticUpdateMsgCount(msgStatus, MessageType.fixed);
            return null;
        }

        // 设备状态已改变时，更新统计信息
        statisticUpdateMsgCount(msgStatus, MessageType.variable);

        //设备状态已改变时，继续更新考勤信息
        long l2 = System.currentTimeMillis();
        //根据设备中记录的考勤表索引，获取并更新员工的考勤表
        if (device.getEmployeeObjectId() != null) {
            dbRoutineOperate.updateRoutineById(device.getEmployeeObjectId(), msgStatus, msgStatus.getType());
        } else {
            logger.info("无指定设备对应的员工，故未更新考勤表");
        }
        long l3 = System.currentTimeMillis();
        if (ServerSetting.DEBUG) {
            logger.info("时间耗费：" + (l2 - l1) + "ms; " + (l3 - l2) + "ms");
        }
        return device;
    }

    private enum MessageType {
        /**
         * 类别总数
         */
        all,
        /**
         * 类别未更改
         */
        fixed,
        /**
         * 类别已更改
         */
        variable
    }
}
