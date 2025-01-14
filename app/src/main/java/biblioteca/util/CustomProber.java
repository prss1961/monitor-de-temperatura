package biblioteca.util;

import biblioteca.driver.ProbeTable;
import biblioteca.driver.UsbSerialProber;

/**
 * add devices here, that are not known to DefaultProber
 *
 * if the App should auto start for these devices, also
 * add IDs to app/src/main/res/xml/device_filter.xml
 */
public class CustomProber {

    public static UsbSerialProber getCustomProber() {
        ProbeTable customTable = new ProbeTable();
        //customTable.addProduct(0x1234, 0x0001, FtdiSerialDriver.class); // e.g. device with custom VID+PID
        //customTable.addProduct(0x1234, 0x0002, FtdiSerialDriver.class); // e.g. device with custom VID+PID
        return new UsbSerialProber(customTable);
    }

}
