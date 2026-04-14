/*
 * NetFTOptions.java
 *
 * Created June 2012
 *
 */

package netftsample;


import java.io.Serializable;

/*
 * Options file for the NetFT demo application.  To add more options data, add
 * a field below and encapsulate the field.  The methods SaveSettings() and
 * LoadSettings() in NetFTSampleGUI are used to serialize the instance
 * of this class, m_demoOptions.
 */

/**
 *
 * @author fleda
 */
public class NetFTOptions implements Serializable{
    //Saves whether the history view chart should be visible
    private boolean showHistoryView = false;
    //Saves the last IP Address connected to
    private String lastIPAddress = null;
    //Saves the last MAC Address connected to
    private String lastMACAddress = null;
    //Saves whether the history chart should be auto scaled
    private boolean autoScaleHistory = true;
    //Saves the duration of sensor data the history chart should display.
    private int historyDuration = 5;

    /**
     * @return the showHistoryView
     */
    public boolean isShowHistoryView() {
        return showHistoryView;
    }

    /**
     * @param showHistoryView the showHistoryView to set
     */
    public void setShowHistoryView(boolean showHistoryView) {
        this.showHistoryView = showHistoryView;
    }

    /**
     * @return the lastIPAddress
     */
    public String getLastIPAddress() {
        return lastIPAddress;
    }

    /**
     * @param lastIPAddress the lastIPAddress to set
     */
    public void setLastIPAddress(String lastIPAddress) {
        this.lastIPAddress = lastIPAddress;
    }

    /**
     * @return the lastMACAddress
     */
    public String getLastMACAddress() {
        return lastMACAddress;
    }

    /**
     * @param lastMACAddress the lastMACAddress to set
     */
    public void setLastMACAddress(String lastMACAddress) {
        this.lastMACAddress = lastMACAddress;
    }

    /**
     * @return the autoScaleHistory
     */
    public boolean isAutoScaleHistory() {
        return autoScaleHistory;
    }

    /**
     * @param autoScaleHistory the autoScaleHistory to set
     */
    public void setAutoScaleHistory(boolean autoScaleHistory) {
        this.autoScaleHistory = autoScaleHistory;
    }

    /**
     * @return the lastHistoryDuration
     */
    public int getHistoryDuration() {
        return historyDuration;
    }

    /**
     * @param lastHistoryDuration the lastHistoryDuration to set
     */
    public void setHistoryDuration(int lastHistoryDuration) {
        this.historyDuration = lastHistoryDuration;
    }
}
