package nodomain.freeyourgadget.gadgetbridge.service.devices.hx03w;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.net.Uri;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.hx03w.Hx03wConstants;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.*;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/*
                                Notes
        HX03W uses AT+ style commands terminated by \r\n
        EACH COMMAND MUST BE SPLIT IN CHUNKS MAX LEN: 20
            example AT+DT command must be divided

                                TIME:

            //Set Current time as AT+DT=YYYYMMDDHHMMSS
            AT+DT=20180101020000

            // Set timezone offset (as Seconds) Ex: +02:00 is 7200 seconds
            AT+TIMEZONE=7200

            //Set 12h (0) or 24h (1)
            AT+TIMEFORMAT=1

            commands must be performed in this exact sequence to have effect

                                ALARMS
            MAX 5 ALARMS

            COMMAND: AT+ALARM=ID HH MM DAYS
            COMMAND: AT+ALARM=04 07 27 7e (7:27) fourth alarm

            ID RANGE 00 -- 04 (count 5)

            HH: 00 -- 23
            MM: 00 -- 59

            DAYS:
                0 Sunday    = 0x80
                1 Monday    = 0x40
                2 Tuesday   = 0x20
                3 Wednesday = 0x10
                4 Thursday  = 0x08
                5 Friday    = 0x04
                6 Saturday  = 0x02

                SINGLE DAY = (128 >> DAYID) + 1 (example Sunday is 0x81)
                MULTIPLE DAYS = (128 >> DAYID1) + (128 >> DAYID2) + (128 >> DAYID...N) + 1

                Examples:
                    Monday: 0x41
                    AllWeek: 0xFF
                to Delete/Disable an Alarm set DAYS to 0xFE

                                    Settings
        Vibration force:
            set the vibration force and smartband vibrates
            Maybe Useful for vibration-encoded messages
            Command: AT+MOTOR=13
                13 -> MAX
                12 -> MID
                11 -> LOW

                                Notifications:
    Send Notification:
        Command: AT+PUSH=<?:INT>,<TEXT:STR>,<?:INT>,<APPID:INT>
        Example: AT+PUSH=1,Hello,0,6
            APPIDs:
                0 -> Call (No text allowed)
                1 -> SMS
                2 -> I don't recognize this icon (?)
                3 -> WeChat
                4 -> Facebook
                5 -> Twitter
                6 -> Whatsapp
                7 -> Instagram
                8 -> Email
                9 -> Line
                10 -> Skype
                ID > 10 -> Fallback on SMS

                                Sensors:
   HRM:
    AT+HEART=1
    // triggers HRM sensors when reading is done device will send a notify through 'HX03W_A_TX' Characteristic
        //Example: 'AT+HEART: 80' means 80BPM

    PEDOMETER:
    AT+PACE

    // triggers PEDOMETER sensor device will send a notify through 'HX03W_A_TX' Characteristic
        //Example: 'AT+PACE: 80' means 80 STEPS

                                Battery:
    Trigger Battery Percentage:
        AT+BATT

        //when reading is done device will send a notify through 'HX03W_A_TX' Characteristic
        Example: 'AT+BATT: 40' means 40%

                                Find My Phone:
    when 'NT+BEEP' is received  through 'HX03W_A_TX' Characteristic phone should start to vibrate,
    making it easier to find.


*/


public class Hx03wDeviceSupport extends AbstractBTLEDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(Hx03wDeviceSupport.class);
    private final GBDeviceEventBatteryInfo batteryCmd = new GBDeviceEventBatteryInfo();
    private BluetoothGattCharacteristic rxCh = null;
    private BluetoothGattCharacteristic txCh = null;

    public Hx03wDeviceSupport() {
        super(LOG);
        addSupportedService(Hx03wConstants.HX03W_A_SERVICE);
    }

    private void writeCmd(BluetoothGattCharacteristic characteristic, String data, TransactionBuilder transaction) {
        data += "\r\n";

        int chunkSize = 20;
        int length = data.length();
        for (int i = 0; i < length; i += chunkSize) {
            transaction.write(characteristic, data.substring(i, Math.min(length, i + chunkSize)).getBytes());
        }
    }

    private void setAlarm(Alarm alarm, int index, TransactionBuilder transaction) {
        String pos = (index > 9) ? String.valueOf(index) : ("0" + index);
        String hour = (alarm.getHour() > 9) ? String.valueOf(alarm.getHour()) : ("0" + alarm.getHour());
        String minutes = (alarm.getMinute() > 9) ? String.valueOf(alarm.getMinute()) : ("0" + alarm.getMinute());
        int outMask = 0;
        if (alarm.getEnabled()) {
            int inMask = alarm.getRepetition();
            //TODO: find a better way to convert mask
            if ((inMask & 0x01) != 0) { //Monday
                outMask += 0x40;
            }
            if ((inMask & 0x02) != 0) { //Tuesday
                outMask += 0x20;
            }
            if ((inMask & 0x04) != 0) { //Wednesday
                outMask += 0x10;
            }
            if ((inMask & 0x08) != 0) { //Thursday
                outMask += 0x08;
            }
            if ((inMask & 0x10) != 0) { //Friday
                outMask += 0x04;
            }
            if ((inMask & 0x20) != 0) { //Saturday
                outMask += 0x02;
            }
            if ((inMask & 0x40) != 0) { //Sunday
                outMask += 0x80;
            }
            outMask += 1;
        } else {
            //Delete/Disable alarm
            outMask = 0xFE;
        }
        String cmd = String.format("AT+ALARM:%s%s%s%x", pos, hour, minutes, outMask);
        LOG.info("Mask:" + outMask);
        LOG.info("cmd:" + cmd);
        writeCmd(rxCh, cmd, transaction);
    }

    private int readValueInt(String value) {
        String tmp = value.split(":")[1];
        if (tmp.equals("ERR")) {
            return -1;
        }
        return Integer.parseInt(tmp);
    }

    private String readValueStr(String value) {
        String tmp = value.split(":")[1];
        if (tmp.equals("ERR")) {
            return "";
        }
        return tmp;
    }

    private void triggerHrmRead(TransactionBuilder transaction) {
        writeCmd(rxCh, "AT+HEART=1", transaction);
    }

    private void triggerBatteryRead(TransactionBuilder transaction) {
        writeCmd(rxCh, "AT+BATT", transaction);
    }

    private void showSyncLogo(boolean show, TransactionBuilder transaction) {
        if (show) {
            writeCmd(rxCh, "AT+SYN=1", transaction);
        } else {
            writeCmd(rxCh, "AT+SYN=0", transaction);
        }
    }

    private void setCurrentDateTime(TransactionBuilder transaction) {
        Calendar c = GregorianCalendar.getInstance();

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmSS");
        dateFormat.setTimeZone(c.getTimeZone());

        //Set Current time as AT+DT=YYYYMMDDHHMMSS
        writeCmd(rxCh, String.format("AT+DT=%s", dateFormat.format(c.getTime())), transaction);

        //Set timezone offset (as Seconds) Ex: +02:00 is 7200 seconds
        writeCmd(rxCh, String.format("AT+TIMEZONE=%d", (int) TimeUnit.MILLISECONDS.toSeconds(c.get(Calendar.ZONE_OFFSET))), transaction);

        //Set 12h (0) or 24h (1)
        //TODO: get this from hx03w specific settings
        writeCmd(rxCh, String.format("AT+TIMEFORMAT=%d", 1), transaction);

    }

    private void sendNotify(String text, int appId) {
        try {
            TransactionBuilder builder = performInitialized("showNotification");
            writeCmd(rxCh, String.format("AT+PUSH=1,%s,0,%d", text, appId), builder);
            performImmediately(builder);
        } catch (IOException e) {
            LOG.warn("Unable to send notification", e);
        }
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        gbDevice.setState(GBDevice.State.INITIALIZING);
        gbDevice.sendDeviceUpdateIntent(getContext());

        rxCh = getCharacteristic(Hx03wConstants.HX03W_A_RX);
        txCh = getCharacteristic(Hx03wConstants.HX03W_A_TX);

        builder.notify(txCh, true);
        showSyncLogo(true, builder);
        setCurrentDateTime(builder);
        triggerBatteryRead(builder);
        showSyncLogo(false, builder);
        gbDevice.setState(GBDevice.State.INITIALIZED);
        gbDevice.sendDeviceUpdateIntent(getContext());
        return builder;
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (super.onCharacteristicChanged(gatt, characteristic)) {
            return true;
        }

        UUID characteristicUUID = characteristic.getUuid();

        if (characteristicUUID.equals(Hx03wConstants.HX03W_A_TX)) {
            String data = new String(characteristic.getValue());
            data = data.replaceAll("\r\n", "");
            if (data.length() == 0) {
                return true;
            }

            if (data.equals("NT+BEEP")) {
                //TODO: let the phone vibrate making easier to find
                LOG.info("BEEP");
            } else if (data.startsWith("AT+HEART:")) {
                int BPM = readValueInt(data);
                getDevice().addDeviceInfo(new GenericItem(getContext().getString(R.string.charts_legend_heartrate) + ": ", String.valueOf(BPM)));

            } else if (data.startsWith("AT+BATT:")) {
                batteryCmd.level = (short) readValueInt(data);
                handleGBDeviceEvent(batteryCmd);

            } else if (data.startsWith("AT+PACE:")) {
                int STEPS = readValueInt(data);
                getDevice().addDeviceInfo(new GenericItem(getContext().getString(R.string.chart_steps) + ": ", String.valueOf(STEPS)));
            } else if (data.startsWith("AT+SN:")) {
                //TODO: handle SerialNumber
                String SN = readValueStr(data);
                LOG.info("SN->" + SN);
            }

        }
        return true;

    }

    @Override
    public void onNotification(NotificationSpec notificationSpec) {
        //Default Icon if a specific one is not available
        int appID = Hx03wConstants.HX03W_APP_EMAIL;
        String text = "";
        //Display is very small text is not easily readable
        //for the moment show only App icon (or app name if icon is not available)

        if (notificationSpec.type == NotificationType.GENERIC_SMS) {
            appID = Hx03wConstants.HX03W_APP_SMS;
        } else if (notificationSpec.type == NotificationType.WECHAT) {
            appID = Hx03wConstants.HX03W_APP_WECHAT;
        } else if (notificationSpec.type == NotificationType.FACEBOOK) {
            appID = Hx03wConstants.HX03W_APP_FACEBOOK;
        } else if (notificationSpec.type == NotificationType.TWITTER) {
            appID = Hx03wConstants.HX03W_APP_TWITTER;
        } else if (notificationSpec.type == NotificationType.WHATSAPP) {
            appID = Hx03wConstants.HX03W_APP_WHATSAPP;
        } else if (notificationSpec.type == NotificationType.INSTAGRAM) {
            appID = Hx03wConstants.HX03W_APP_INSTAGRAM;
        } else if (notificationSpec.type == NotificationType.SNAPCHAT) {
            appID = Hx03wConstants.HX03W_APP_SNAPCHAT;
        } else if (notificationSpec.type == NotificationType.GENERIC_EMAIL) {
            appID = Hx03wConstants.HX03W_APP_EMAIL;
        } else if (notificationSpec.type == NotificationType.LINE) {
            appID = Hx03wConstants.HX03W_APP_LINE;
        } else if (notificationSpec.type == NotificationType.SKYPE) {
            appID = Hx03wConstants.HX03W_APP_SKYPE;
        } else {
            // If icon is not available show appName as text
            text = notificationSpec.sourceName;
        }
        sendNotify(text, appID);
    }

    @Override
    public void onDeleteNotification(int id) {

    }

    @Override
    public void onSetTime() {
        try {
            TransactionBuilder builder = performInitialized("SetTime");
            setCurrentDateTime(builder);
            builder.queue(getQueue());
        } catch (IOException e) {
            LOG.warn("onSetTime failed: " + e.getMessage());
        }

    }

    @Override
    public void onSetAlarms(ArrayList<? extends Alarm> alarms) {

        TransactionBuilder builder;
        try {
            builder = performInitialized("SetAlarms");
            for (Alarm current : alarms) {
                setAlarm(current, current.getPosition(), builder);
            }
            builder.queue(getQueue());
        } catch (IOException e) {
            LOG.warn("onSetAlarm failed: " + e.getMessage());
        }
    }

    @Override
    public void onSetCallState(CallSpec callSpec) {
        if (callSpec.command == CallSpec.CALL_INCOMING) {
            sendNotify("", Hx03wConstants.HX03W_APP_CALL);
        }
    }

    @Override
    public void onSetCannedMessages(CannedMessagesSpec cannedMessagesSpec) {

    }

    @Override
    public void onSetMusicState(MusicStateSpec stateSpec) {

    }

    @Override
    public void onSetMusicInfo(MusicSpec musicSpec) {

    }

    @Override
    public void onEnableRealtimeSteps(boolean enable) {

    }

    @Override
    public void onInstallApp(Uri uri) {

    }

    @Override
    public void onAppInfoReq() {

    }

    @Override
    public void onAppStart(UUID uuid, boolean start) {

    }

    @Override
    public void onAppDelete(UUID uuid) {

    }

    @Override
    public void onAppConfiguration(UUID appUuid, String config, Integer id) {

    }

    @Override
    public void onAppReorder(UUID[] uuids) {

    }

    @Override
    public void onFetchRecordedData(int dataTypes) {

    }

    @Override
    public void onReset(int flags) {

    }

    @Override
    public void onHeartRateTest() {
        try {
            TransactionBuilder builder = performInitialized("HeartRateTest");
            triggerHrmRead(builder);
            builder.queue(getQueue());
        } catch (Exception e) {
            LOG.warn("HRM_READ: Failed");

        }

    }

    @Override
    public void onEnableRealtimeHeartRateMeasurement(boolean enable) {

    }

    @Override
    public void onFindDevice(boolean start) {

    }

    @Override
    public void onSetConstantVibration(int integer) {

    }

    @Override
    public void onScreenshotReq() {

    }

    @Override
    public void onEnableHeartRateSleepSupport(boolean enable) {

    }

    @Override
    public void onSetHeartRateMeasurementInterval(int seconds) {

    }

    @Override
    public void onAddCalendarEvent(CalendarEventSpec calendarEventSpec) {

    }

    @Override
    public void onDeleteCalendarEvent(byte type, long id) {

    }

    @Override
    public void onSendConfiguration(String config) {

    }

    @Override
    public void onTestNewFunction() {

    }

    @Override
    public void onSendWeather(WeatherSpec weatherSpec) {

    }
}
