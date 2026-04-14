/*
 * NetFTSampleGUI.java
 *
 * Created on February 8, 2006, 3:32 PM
 *
 * Modifications:
 * jun.03.2010          Sam Skuce (ATI Industrial Automation)
 *  Tweaked data formatting in collection file, added caveat about RDT sample to
 * collection file.
 */


package netftsample;
import com.atiia.automation.sensors.FTVisualizationCube;
import com.atiia.automation.sensors.NetFTRDTPacket;
import com.atiia.automation.sensors.NetFTSensor;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.*;
import java.net.*;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.Calendar;
import javax.swing.*;

/**GUI for Net F/T sample application
 *
 * @author  Sam Skuce (ATI Industrial Automation)
 */
public class NetFTSampleGUI extends javax.swing.JFrame {
    
    /** Version of this GUI. */
    private static final String VERSION = "1.1.1";
    
    /** Copyright year. */
    private static final String COPYRIGHT = "2013";
    
    private double[] m_daftMaxes = { 100, 100, 100, 100, 100, 100 }; /*maximum 
     rated force/torque readings*/
    private double[] m_daftCountsPerUnit = {1, 1, 1, 1, 1, 1}; /*counts per 
        *unit force or torque for each axis*/
    /** Visualizes the forces and torques. */
    private FTVisualizationCube m_ftvc;
    
    private String m_strForceUnits; /*The units of force.*/
    private String m_strTorqueUnits; /*The units of torque.*/
    private String m_strCalSN;
    private String m_strCalIndex;
    private String m_strCfgName;
    private String m_strCfgIndex;
    
    private JLabel[] m_lblaFTLabel; /*the labels that display the force/torque 
     readings*/
    private String m_strSensorAddress; /*The network address of the sensor.*/
    private JProgressBar[] m_progaFTReadingBar; /*the progress bars */
    private static final int NUM_FT_AXES = 6;
    private static final int FX_INDEX = 0, FY_INDEX = 1, FZ_INDEX = 2, 
            TX_INDEX = 3, TY_INDEX = 4, TZ_INDEX = 5;
    private static final String[] MAX_RATED_PARAMETER_NAME = {"MAXFX", "MAXFY", 
            "MAXFZ", "MAXTX", "MAXTY", "MAXTZ" };    
    private static final Color POSITIVE_COLOR = Color.blue, 
            NEGATIVE_COLOR = Color.green;
    private DecimalFormat m_dfReading;
    private NetFTSensor m_netFT; /*the Net F/T controller*/
    private NetFTReaderThread m_NetFTReaderThread = null; /*reads 
     Net F/T in a loop*/
    private DatagramSocket m_cNetFTFastSocket; /*socket used for high-speed
                                                *data collection*/  
    private DatagramSocket m_cNetFTSlowSocket;
    private boolean m_bDoingHighSpeedCollection = false; /*whether or not
    *we're currently doing high speed data collection*/    
    private boolean m_recordNextCollection = false;
    private java.io.PrintWriter m_cDataWriter; /*writes high-speed data to
    file*/
    
    /** The RDT sample rate of the sensor. */
    private int m_iRDTSampleRate = 0;

    private NetFTOptions m_demoOptions = null;
    private String m_optionsFileLocation = "ATINetFTDemoOptions.xml";
    
    //Window Resizing
    private int m_lastWindowHeight = 0;
    private int m_lastWindowWidth = 0;
    
    private int m_maxHistoryDuration = 120;
    
    //////Graphing Objects//////
    private MovingLineChart drawingPanel = new MovingLineChart();
    private Graphics graphics = null;
    private Graphics2D g2d = null;
    private GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
    private GraphicsDevice gd = ge.getDefaultScreenDevice();
    private GraphicsConfiguration gc = gd.getDefaultConfiguration();
    private BufferedImage bi = gc.createCompatibleImage(801, 601);
    ////////////////////////////

    /**Thread which communicates with Net F/T
     */
    private class NetFTReaderThread extends Thread{
        
        private NetFTSensor m_netFT; /*the Net F/T controller*/
        private NetFTSampleGUI m_guiParent; /*the gui that is using this
                                           *thread*/                                           
        
        private boolean m_bKeepGoing = true; /*controls when to end this
                                              *thread*/
        
        
        /**
         * Creates a new Net F/T reader thread
         * @param setNetFT The initialized NetFTSensor to communicate with.
         * @param setParent The NetFTSampleGUI that is using this thread to
         * communicate with the Net F/T.
         */
        public NetFTReaderThread( NetFTSensor setNetFT, 
                NetFTSampleGUI setParent ){
            m_netFT = setNetFT;
            m_guiParent = setParent;
        }   
        
        /**Sets a flag to stop after the current read is complete*/
        public void stopReading(){
            m_bKeepGoing = false;
            this.interrupt(); /*wake this thread up if it's in it's sleeping
                               *phase*/
        }


        /**Reads the Net F/T sensor in a loop*/
        @Override
        public void run(){
            NetFTRDTPacket cRDTData; /*the latest f/t data from the Net F/T*/
            while ( m_bKeepGoing ){                
                try{
                    Thread.yield(); /* Attempt to allow user button press to take effect. */
                    /*synchronize this in case they press the stop button
                         *while we're in the middle of reading the data*/
                    synchronized ( m_netFT ){
                        if ( m_bDoingHighSpeedCollection ){                            
                            /*read batch RDT packets*/ 
                            int PACKETS_TO_READ = Math.max(m_iRDTSampleRate / 10, 1); /* Try to avoid excessive delays while reading. */
                            NetFTRDTPacket[] caRDTPackets = new 
                                NetFTRDTPacket[PACKETS_TO_READ];                        
                            caRDTPackets = m_netFT.readHighSpeedData( 
                                    m_cNetFTFastSocket, PACKETS_TO_READ );  
                            int i; /*generic loop/array index*/
                            /*Precondition: caRDTPackets contains the list of
                             *packets that were read from the Net F/T. 
                            *m_cDataWriter is open to file to collect data to.
                            *Postcondition: m_cDataWriter has the new F/T data.
                            *i == PACKETS_TO_READ.
                            */
                            for ( i = 0; i < PACKETS_TO_READ; i++ ){                                 
                                m_cDataWriter.println( dataCollectionLine( caRDTPackets[i] ) );
                            }
                            cRDTData = caRDTPackets[PACKETS_TO_READ - 1];
                        }else{
                            cRDTData = m_netFT.readLowSpeedData(m_cNetFTSlowSocket);
                            if(m_recordNextCollection){
                                m_cDataWriter.println(m_guiParent.dataCollectionLine(cRDTData)+", "+
                                        lblForceUnits.getText() +", "+
                                        lblTorqueUnits.getText());
                                m_cDataWriter.close();
                            }
                        }                        
                    }
                    m_guiParent.displayFTData( cRDTData );
                }catch ( SocketException sexc ){
                    m_guiParent.displayError("Socket Exception: " + 
                            sexc.getMessage() );
                }catch ( IOException iexc ){
                    m_guiParent.displayError( "IO Exception: " +
                            iexc.getMessage() );                      
                }
                try{
                    if ( !m_bDoingHighSpeedCollection ) {
                        Thread.sleep(100);
                    }
                }catch ( java.lang.InterruptedException iexc ){
                    /*do nothing, just continue.  This exception should
                     only be thrown if they try to stop the thread while
                     it's sleeping*/
                }
                
            }
        }
    }
    
    /**Formats a data packet for collection to file.
     * @param nftrdtp   The data packet to record to file.
     * @return  A formatted string containing the F/T data and current time, 
     * which can be written to file.
     */
    private String dataCollectionLine(NetFTRDTPacket nftrdtp)
    {    
        
        String outputString =  /* The formatted output line. */                                   
                "\"0x" + Integer.toHexString(nftrdtp.getStatus()) + "\",\"" +
                nftrdtp.getRDTSequence() + "\",\"" +
                nftrdtp.getFTSequence() + "\",\"" +
                (nftrdtp.getFx()/m_daftCountsPerUnit[0]) + "\",\"" +
                (nftrdtp.getFy()/m_daftCountsPerUnit[1])  + "\",\"" +
                (nftrdtp.getFz()/m_daftCountsPerUnit[2])  + "\",\"" + 
                (nftrdtp.getTx()/m_daftCountsPerUnit[3])  + "\",\"" +
                (nftrdtp.getTy()/m_daftCountsPerUnit[4])  + "\",\"" +
                (nftrdtp.getTz()/m_daftCountsPerUnit[5])  + "\",\"" +
                DateFormat.getDateTimeInstance().format(Calendar.getInstance().getTime()) + "\"";
        return outputString;
    }
    
    /** Creates new form NetFTSampleGUI */
    public NetFTSampleGUI() {
        initComponents();
        //load saved settings
        loadSettings();
        m_ftvc = new FTVisualizationCube();
        this.getContentPane().add(m_ftvc);
        visualCubePane.setLayout(new BorderLayout(0, 0));
        visualCubePane.add(m_ftvc, BorderLayout.CENTER);
        
        m_ftvc.setYaw(90);//all of these rotations dont affect SG data, apparently affect mouse dragging
        m_ftvc.setPitch(20);//
        m_ftvc.setRoll(70);//
        m_dfReading= new DecimalFormat( "#.000");        
        m_lblaFTLabel = new JLabel[] { lblFx, lblFy, lblFz, lblTx, lblTy, 
                            lblTz };
        m_progaFTReadingBar = new JProgressBar[] { progFx, progFy, progFz, 
                            progTx, progTy, progTz };
        
        //load sensor address
        m_strSensorAddress = m_demoOptions.getLastIPAddress();
        
        
        URL urlIconPath = getClass().getResource("ati1.jpg"); //ClassLoader.getSystemClassLoader().getSystemResource("ati1.ico");
        Image img = Toolkit.getDefaultToolkit().getImage(urlIconPath);        
        if (null != img) setIconImage(img);
        
        //Load last view mode
        if(m_demoOptions.isShowHistoryView()){
            standardPanel.setVisible(false);
            standardViewMenuItem.setState(false);
            historyViewMenuItem.setState(true);
            jPanel1.setVisible(true);
        }
        else{
            standardPanel.setVisible(true);
            standardViewMenuItem.setState(true);
            historyViewMenuItem.setState(false);
            jPanel1.setVisible(false);
        }
        
        //Load last history duration
        jTextFieldHistoryDuration.setText(Integer.toString(m_demoOptions.getHistoryDuration()));
        
        //Load auto scaling setting for the menu
        if (m_demoOptions.isAutoScaleHistory()){
            jCheckBoxAutoScaleHistory.setSelected(true);
        }
        else{
            jCheckBoxAutoScaleHistory.setSelected(false);
        }
        
        //////Graphing Objects//////
        //drawingPanel.setIgnoreRepaint(true);
        
        //drawingPanel.setBounds(0, 0, jLayeredPane2.getSize().width, jLayeredPane2.getSize().height);
        drawingPanel.setArraySize(m_demoOptions.getHistoryDuration()*10);
        ////////////////////////////
        
        saveSettings();
        stopAndRestartSingleReadings();
    }
    
    public NetFTSampleGUI(String titleString){
        this();
        this.setTitle(titleString);
    }

    /**
     * Stops the F/T reader thread, and sets m_NetFTReaderThread = 
     * null.
     */
    private void stopReaderThread()
    {
        if ( null != m_NetFTReaderThread ){
            m_NetFTReaderThread.stopReading();
            m_NetFTReaderThread = null;
        }
    }
    
    /**Stops (if necessary) the net f/t reader thread, and restarts it using
     *the user options. Also sets the scaling factors for the f/t display.
     */
    private void stopAndRestartSingleReadings(){
        
        if ( null != m_NetFTReaderThread ){
            m_NetFTReaderThread.stopReading();   
            m_NetFTReaderThread = null;
        }
        try{
            m_netFT = new NetFTSensor( InetAddress.getByName( 
                                m_strSensorAddress ) );
        } catch ( UnknownHostException uhex ){
            displayError( "Unknown Host Exception: " + 
                                uhex.getMessage() );            
            return;
        }       
        if ( ! readConfigurationInfo() ) {
            return;
        }
        lblForceUnits.setText( "Force Units: " + m_strForceUnits );
        lblTorqueUnits.setText( "Torque Units: " + m_strTorqueUnits );
        lblCalIndex.setText("Calibration Index: " + (Integer.parseInt(m_strCalIndex) + 1));
        lblConfigIndex.setText("Config Index: " + (Integer.parseInt(m_strCfgIndex) + 1));
        lblCalSN.setText("Calibration Serial#: " + m_strCalSN);
        lblConfigName.setText("Config Name: " + m_strCfgName);
        //insert
         try{
            m_cNetFTSlowSocket = m_netFT.initLowSpeedData();
        }catch ( SocketException sexc ){
            displayError( "SocketException: " + sexc.getMessage());
        }catch ( IOException iexc ){
            displayError( "IOException: " + iexc.getMessage());
        }
        StartNetFTReaderThread();
        
    }

    /**
     * Starts net F/T reader thread.
     */
    private void StartNetFTReaderThread()
    {
        m_NetFTReaderThread = new NetFTReaderThread( m_netFT, this );        
        m_NetFTReaderThread.start();

    }
    
    /**
     *Reads a page from the integrated web server.
     *@param strUrlSuffix   The page on the web server to read.
     *@return  The text of the web page.
     *@throws MalformedURLException If strUrlSuffix doesn't point to a valid
     *web page address.
     *@throws IOException If there is an error reading the web page text.
     */
    private String readWebPageText( String strUrlSuffix ) throws 
          MalformedURLException, IOException
    {
        /*Reads the HTML from the web server.*/
        BufferedReader cBufferedReader;
        /*The url of the configuration page.*/
        String strURL = "http://" + m_strSensorAddress + "/" +
                strUrlSuffix;
        cBufferedReader = new BufferedReader ( new InputStreamReader ( new
                URL(strURL).openConnection().getInputStream()));        
        /*The text of the page.*/
        String strPageText = "";
        /*The last line read from the web stream.*/
        String strCurLine;
        /*Precondition: cBufferedReader is at the beginning of the page.
         *Postcondition: cBufferedReader is finished, strPageText =
         *the text of the page, strCurLine = last line read from the 
         *page.
         */
         while ( null != ( strCurLine = cBufferedReader.readLine() ) ) {            
            strPageText += strCurLine;
         }     
        return strPageText;
    }
    
    private String readNetFTAPI(int index)
    {
        try{
        String strXML = readWebPageText("netftapi2.xml?index="+index);
        return strXML;
        }catch(Exception e)
        {
            return "";
        }
    }
    
        private String readNetFTCalAPI(int index)
    {
        try{
        String strXML = readWebPageText("netftcalapi.xml?index="+index);
        return strXML;
        }catch(Exception e)
        {
            return "";
        }
    }
    
    private int findActiveCFG(String xmlText)
    {
       String[] strret = xmlText.split("<setcfgsel>");
       String[] strret2 = strret[1].split("</setcfgsel>");
       int activeConfig = Integer.parseInt(strret2[0]);
       return activeConfig;       
    }
    
    /**
     *Reads information about the sensor's configuration from the integrated
     *web server.
     *@return  True if configuration was successfully read, false otherwise.
     */
    private boolean readConfigurationInfo()
    { 
        try
        {
        String mDoc = readNetFTAPI(0);
        int activeConfig = findActiveCFG(mDoc);
        mDoc = readNetFTAPI(activeConfig);
        m_strCfgIndex = "" + activeConfig;
        String[] parseStep1 = mDoc.split("<cfgcalsel>");
        String[] parseStep2 = parseStep1[1].split("</cfgcalsel>");
        String mCal = readNetFTCalAPI(Integer.parseInt(parseStep2[0]));
        m_strCalIndex = parseStep2[0];
        parseStep1 = mCal.split("<calsn>");
        parseStep2 = parseStep1[1].split("</calsn>");
        m_strCalSN = parseStep2[0];
        mDoc = readNetFTAPI(activeConfig);
        parseStep1 = mDoc.split("<cfgnam>");
        parseStep2 = parseStep1[1].split("</cfgnam>");
        m_strCfgName = parseStep2[0];        
        parseStep1 = mDoc.split("<cfgcpf>");
        parseStep2 = parseStep1[1].split("</cfgcpf>");        
        setCountsPerForce(Double.parseDouble(parseStep2[0]));
        parseStep1 = mDoc.split("<cfgcpt>");
        parseStep2 = parseStep1[1].split("</cfgcpt>");       
        setCountsPerTorque(Double.parseDouble(parseStep2[0]));
        parseStep1 = mDoc.split("<comrdtrate>");
        parseStep2 = parseStep1[1].split("</comrdtrate>");  
        m_iRDTSampleRate = (Integer.parseInt(parseStep2[0]));
        parseStep1 = mDoc.split("<scfgfu>");
        parseStep2 = parseStep1[1].split("</scfgfu>"); 
        m_strForceUnits = parseStep2[0];
        parseStep1 = mDoc.split("<scfgtu>");
        parseStep2 = parseStep1[1].split("</scfgtu>"); 
        m_strTorqueUnits = parseStep2[0];
        parseStep1 = mDoc.split("<cfgmr>");
        parseStep2 = parseStep1[1].split("</cfgmr>");
        String[] asRatings = parseStep2[0].split(";");
          for ( int i = 0; i < asRatings.length; i++ )
          {
              m_daftMaxes[i] = Double.parseDouble(asRatings[i]);
              if ( 0 == m_daftMaxes[i])
              {
                   m_daftMaxes[i] = 32768; /* Default maximum rating. */
              }
           }
           m_ftvc.setMaxForce(m_daftMaxes[2]); /* Use Fz rating as maximum. */
           m_ftvc.setMaxTorque(m_daftMaxes[5]); /* use Tz rating as maximum. */
        }catch(Exception e)
        {
            return false;            
        }
        return true;
    } 
    
    /**
     *Sets the maximum ratings for each axis.
     *@param strConfigPageText  The HTML code of the configuration page on the
     *integrated web server.
     */
    
    private void setCountsPerForce( double counts )
    {
        double dCountsPerForce = counts;
        if ( 0 == dCountsPerForce ){
            dCountsPerForce = 1;
            displayError( "Read a counts per force value of 0, setting it to " +
                    "1 instead.");
        }
        int i;
        for ( i = 0; i < 3; i++ )
        {
            m_daftCountsPerUnit[i] = dCountsPerForce;
        }
    }
    
    private void setCountsPerTorque( double counts )
    {
        double dCountsPerTorque = counts;
        if ( 0 == dCountsPerTorque ) {
            dCountsPerTorque = 1;
            displayError( "Read a counts per torque value of 0, setting it " +
                    "to 1 instead." );
        }
        int i; /*generic loop/array index.*/
        /*Precondition: dCountsPerForce has the counts per force, 
         *dCountsPerTorque is the counts per torque.
         *Postcondition: m_daftCountsPerUnit has the counts per unit for each
         *axis, i == 3.
         */
        for ( i = 0; i < 3; i++ )
        {
            m_daftCountsPerUnit[i+3] = dCountsPerTorque;
        }
    }
    
    /**
     *Get the substring between two other substrings
     *@param strSearchText  The text which contains the prefix, value and
     *suffix.
     *@param strPrefix      The text which occurs just before the value you
     *wish to read.
     *@param strSuffix      The text which occurs just after the value you wish
     *to read.
     *@return  The substring of strSearchText that occurs between the FIRST
     *occurrence of strPrefix and the first occurrence of strSuffix after that.
     */
    private String subStringBetween( String strSearchText, String strPrefix,
            String strSuffix)
    {
        /*The position at which the desired text begins.*/
        int iStartIndex = strSearchText.indexOf( strPrefix ) + 
                strPrefix.length();
        /*Ths position at which the suffix starts.*/
        int iSuffixIndex = strSearchText.indexOf( strSuffix, iStartIndex );
        return strSearchText.substring( iStartIndex, iSuffixIndex );
    }

    private void loadSettings(){
        //Load Settings
        m_demoOptions = null;

        //Read in from options file
        try{
            FileInputStream fis = new FileInputStream(m_optionsFileLocation);
            BufferedInputStream bis = new BufferedInputStream(fis);
            XMLDecoder xmlDecoder = new XMLDecoder(bis);
            m_demoOptions = (NetFTOptions)xmlDecoder.readObject();
            xmlDecoder.close();
        }
        catch(FileNotFoundException fnfe){
            System.out.println("Options file not found, using default settings.");
        }
        catch(Exception e){
         System.out.println(e);
        }

        //generate new options object and init if no file found
        if(m_demoOptions == null){
            m_demoOptions = new NetFTOptions();
        }
    }

    private void saveSettings(){
        //save options file
        try{
            FileOutputStream fos = new FileOutputStream(m_optionsFileLocation);
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            XMLEncoder e = new XMLEncoder(bos);
            e.writeObject(m_demoOptions);
            e.close();
        }
        catch(Exception e){
            System.out.println(e);
        }
    }

    /** This method is called from within the constructor to
     * initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is
     * always regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        dialogLogData = new javax.swing.JDialog();
        btnCollect = new javax.swing.JButton();
        txtFileName1 = new javax.swing.JTextField();
        btnSelectFile1 = new javax.swing.JButton();
        jPanel3 = new javax.swing.JPanel();
        jPanel4 = new javax.swing.JPanel();
        jLabel4 = new javax.swing.JLabel();
        lblStatus = new javax.swing.JLabel();
        lblFx = new javax.swing.JLabel();
        jLabel3 = new javax.swing.JLabel();
        jLabel2 = new javax.swing.JLabel();
        lblFy = new javax.swing.JLabel();
        jLabel6 = new javax.swing.JLabel();
        lblTx = new javax.swing.JLabel();
        lblFz = new javax.swing.JLabel();
        jLabel5 = new javax.swing.JLabel();
        lblTz = new javax.swing.JLabel();
        lblTy = new javax.swing.JLabel();
        jLabel1 = new javax.swing.JLabel();
        jLabel9 = new javax.swing.JLabel();
        jPanel5 = new javax.swing.JPanel();
        jLabel7 = new javax.swing.JLabel();
        lblRDTSeq = new javax.swing.JLabel();
        jLabel8 = new javax.swing.JLabel();
        lblFTSeq = new javax.swing.JLabel();
        jPanel1 = new javax.swing.JPanel();
        jLabel11 = new javax.swing.JLabel();
        jTextFieldHistoryDuration = new javax.swing.JTextField();
        jButtonApplyHistoryDuration = new javax.swing.JButton();
        jButtonResetGraph = new javax.swing.JButton();
        jLayeredPane2 = new javax.swing.JLayeredPane();
        standardPanel = new javax.swing.JPanel();
        progTz = new javax.swing.JProgressBar();
        progFz = new javax.swing.JProgressBar();
        progTx = new javax.swing.JProgressBar();
        progTy = new javax.swing.JProgressBar();
        progFy = new javax.swing.JProgressBar();
        progFx = new javax.swing.JProgressBar();
        jPanel2 = new javax.swing.JPanel();
        lblConfigName = new javax.swing.JLabel();
        btnSelectFile = new javax.swing.JButton();
        jLabel10 = new javax.swing.JLabel();
        scrollErrors = new javax.swing.JScrollPane();
        lstErrors = new javax.swing.JList();
        lstErrors.setModel( new DefaultListModel() );
        lblCalIndex = new javax.swing.JLabel();
        txtFileName = new javax.swing.JTextField();
        lblCalSN = new javax.swing.JLabel();
        btnClear = new javax.swing.JButton();
        jButton1 = new javax.swing.JButton();
        lblForceUnits = new javax.swing.JLabel();
        lblTorqueUnits = new javax.swing.JLabel();
        lblConfigIndex = new javax.swing.JLabel();
        btnDataPoint = new javax.swing.JButton();
        visualCubePane = new javax.swing.JLayeredPane();
        jMenuBar1 = new javax.swing.JMenuBar();
        jMenu2 = new javax.swing.JMenu();
        standardViewMenuItem = new javax.swing.JCheckBoxMenuItem();
        historyViewMenuItem = new javax.swing.JCheckBoxMenuItem();
        jSeparator1 = new javax.swing.JPopupMenu.Separator();
        jCheckBoxAutoScaleHistory = new javax.swing.JCheckBoxMenuItem();
        menuDataLog = new javax.swing.JMenu();
        jMenu1 = new javax.swing.JMenu();
        jMenuItem1 = new javax.swing.JMenuItem();

        dialogLogData.setMinimumSize(new java.awt.Dimension(230, 120));
        dialogLogData.getContentPane().setLayout(null);

        btnCollect.setText("Collect Streaming");
        btnCollect.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCollectActionPerformed(evt);
            }
        });
        dialogLogData.getContentPane().add(btnCollect);
        btnCollect.setBounds(10, 40, 160, 23);

        txtFileName1.setText("<please select a file>");
        dialogLogData.getContentPane().add(txtFileName1);
        txtFileName1.setBounds(10, 10, 128, 20);

        btnSelectFile1.setText("...");
        btnSelectFile1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSelectFile1ActionPerformed(evt);
            }
        });
        dialogLogData.getContentPane().add(btnSelectFile1);
        btnSelectFile1.setBounds(140, 10, 31, 20);

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("ATI Industrial Automation Net F/T Demo");
        setIconImage(Toolkit.getDefaultToolkit().getImage(getClass().getResource("ati1.jpg")));
        setMinimumSize(new java.awt.Dimension(636, 780));
        setPreferredSize(new java.awt.Dimension(636, 780));
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent evt) {
                formWindowClosing(evt);
            }
        });
        addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent evt) {
                formComponentResized(evt);
            }
        });
        getContentPane().setLayout(null);

        jPanel3.setLayout(null);

        jPanel4.setLayout(null);

        jLabel4.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        jLabel4.setText("Tx");
        jPanel4.add(jLabel4);
        jLabel4.setBounds(0, 135, 40, 14);

        lblStatus.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblStatus.setText("0");
        lblStatus.setBorder(javax.swing.BorderFactory.createBevelBorder(javax.swing.border.BevelBorder.LOWERED));
        jPanel4.add(lblStatus);
        lblStatus.setBounds(50, 11, 100, 18);

        lblFx.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblFx.setText("0");
        lblFx.setBorder(javax.swing.BorderFactory.createBevelBorder(javax.swing.border.BevelBorder.LOWERED));
        jPanel4.add(lblFx);
        lblFx.setBounds(50, 47, 100, 18);

        jLabel3.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        jLabel3.setText("Fz");
        jPanel4.add(jLabel3);
        jLabel3.setBounds(0, 105, 40, 14);

        jLabel2.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        jLabel2.setText("Fy");
        jPanel4.add(jLabel2);
        jLabel2.setBounds(0, 77, 40, 14);

        lblFy.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblFy.setText("0");
        lblFy.setBorder(javax.swing.BorderFactory.createBevelBorder(javax.swing.border.BevelBorder.LOWERED));
        jPanel4.add(lblFy);
        lblFy.setBounds(50, 77, 100, 18);

        jLabel6.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        jLabel6.setText("Tz");
        jPanel4.add(jLabel6);
        jLabel6.setBounds(0, 195, 40, 14);

        lblTx.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblTx.setText("0");
        lblTx.setBorder(javax.swing.BorderFactory.createBevelBorder(javax.swing.border.BevelBorder.LOWERED));
        jPanel4.add(lblTx);
        lblTx.setBounds(50, 137, 100, 18);

        lblFz.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblFz.setText("0");
        lblFz.setBorder(javax.swing.BorderFactory.createBevelBorder(javax.swing.border.BevelBorder.LOWERED));
        jPanel4.add(lblFz);
        lblFz.setBounds(50, 107, 100, 18);

        jLabel5.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        jLabel5.setText("Ty");
        jPanel4.add(jLabel5);
        jLabel5.setBounds(0, 165, 40, 14);

        lblTz.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblTz.setText("0");
        lblTz.setBorder(javax.swing.BorderFactory.createBevelBorder(javax.swing.border.BevelBorder.LOWERED));
        jPanel4.add(lblTz);
        lblTz.setBounds(50, 197, 100, 18);

        lblTy.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblTy.setText("0");
        lblTy.setBorder(javax.swing.BorderFactory.createBevelBorder(javax.swing.border.BevelBorder.LOWERED));
        jPanel4.add(lblTy);
        lblTy.setBounds(50, 167, 100, 18);

        jLabel1.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        jLabel1.setText("Fx");
        jPanel4.add(jLabel1);
        jLabel1.setBounds(0, 47, 40, 14);

        jLabel9.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        jLabel9.setText("Status");
        jPanel4.add(jLabel9);
        jLabel9.setBounds(0, 13, 40, 14);

        jPanel3.add(jPanel4);
        jPanel4.setBounds(10, 0, 160, 220);

        jPanel5.setLayout(null);

        jLabel7.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        jLabel7.setText("FTSeq");
        jPanel5.add(jLabel7);
        jLabel7.setBounds(168, 13, 40, 14);

        lblRDTSeq.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblRDTSeq.setText("0");
        lblRDTSeq.setBorder(javax.swing.BorderFactory.createBevelBorder(javax.swing.border.BevelBorder.LOWERED));
        jPanel5.add(lblRDTSeq);
        lblRDTSeq.setBounds(62, 11, 100, 18);

        jLabel8.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        jLabel8.setText("RDTSeq");
        jPanel5.add(jLabel8);
        jLabel8.setBounds(0, 13, 56, 14);

        lblFTSeq.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblFTSeq.setText("0");
        lblFTSeq.setBorder(javax.swing.BorderFactory.createBevelBorder(javax.swing.border.BevelBorder.LOWERED));
        jPanel5.add(lblFTSeq);
        lblFTSeq.setBounds(214, 11, 100, 18);

        jPanel3.add(jPanel5);
        jPanel5.setBounds(176, 0, 370, 33);

        jPanel1.setMinimumSize(new java.awt.Dimension(100, 30));
        jPanel1.setLayout(null);

        jLabel11.setText("<html>History<br>Duration (sec.):");
        jPanel1.add(jLabel11);
        jLabel11.setBounds(0, 0, 90, 30);

        jTextFieldHistoryDuration.setText("5");
        jTextFieldHistoryDuration.addKeyListener(new java.awt.event.KeyAdapter() {
            public void keyTyped(java.awt.event.KeyEvent evt) {
                jTextFieldHistoryDurationKeyTyped(evt);
            }
        });
        jPanel1.add(jTextFieldHistoryDuration);
        jTextFieldHistoryDuration.setBounds(90, 10, 30, 20);

        jButtonApplyHistoryDuration.setText("Apply");
        jButtonApplyHistoryDuration.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonApplyHistoryDurationActionPerformed(evt);
            }
        });
        jPanel1.add(jButtonApplyHistoryDuration);
        jButtonApplyHistoryDuration.setBounds(0, 40, 70, 23);

        jButtonResetGraph.setText("Reset Graph");
        jButtonResetGraph.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButtonResetGraphActionPerformed(evt);
            }
        });
        jPanel1.add(jButtonResetGraph);
        jButtonResetGraph.setBounds(0, 70, 110, 23);

        jPanel3.add(jPanel1);
        jPanel1.setBounds(10, 232, 150, 109);

        standardPanel.setMaximumSize(new java.awt.Dimension(330, 190));
        standardPanel.setPreferredSize(new java.awt.Dimension(330, 190));
        standardPanel.setLayout(null);

        progTz.setMaximumSize(null);
        progTz.setMinimumSize(new java.awt.Dimension(10, 18));
        progTz.setPreferredSize(new java.awt.Dimension(170, 18));
        standardPanel.add(progTz);
        progTz.setBounds(10, 158, 400, 18);

        progFz.setMaximumSize(null);
        progFz.setMinimumSize(new java.awt.Dimension(10, 18));
        progFz.setPreferredSize(new java.awt.Dimension(146, 18));
        standardPanel.add(progFz);
        progFz.setBounds(10, 68, 400, 18);

        progTx.setMaximumSize(null);
        progTx.setMinimumSize(new java.awt.Dimension(10, 18));
        progTx.setPreferredSize(new java.awt.Dimension(146, 18));
        standardPanel.add(progTx);
        progTx.setBounds(10, 98, 400, 18);

        progTy.setMaximumSize(null);
        progTy.setMinimumSize(new java.awt.Dimension(10, 18));
        progTy.setPreferredSize(new java.awt.Dimension(146, 18));
        standardPanel.add(progTy);
        progTy.setBounds(10, 128, 400, 18);

        progFy.setMaximumSize(null);
        progFy.setMinimumSize(new java.awt.Dimension(10, 18));
        progFy.setPreferredSize(new java.awt.Dimension(146, 18));
        standardPanel.add(progFy);
        progFy.setBounds(10, 38, 400, 18);

        progFx.setMaximumSize(null);
        progFx.setMinimumSize(new java.awt.Dimension(10, 18));
        progFx.setPreferredSize(new java.awt.Dimension(146, 18));
        standardPanel.add(progFx);
        progFx.setBounds(10, 8, 400, 18);

        standardPanel.setBounds(0, 0, 420, 210);
        jLayeredPane2.add(standardPanel, javax.swing.JLayeredPane.DEFAULT_LAYER);

        jPanel3.add(jLayeredPane2);
        jLayeredPane2.setBounds(176, 39, 434, 250);

        getContentPane().add(jPanel3);
        jPanel3.setBounds(0, 0, 620, 380);

        jPanel2.setMaximumSize(new java.awt.Dimension(32767, 320));
        jPanel2.setMinimumSize(new java.awt.Dimension(620, 320));
        jPanel2.setName("");
        jPanel2.setLayout(null);

        lblConfigName.setText("Config Name: ?");
        jPanel2.add(lblConfigName);
        lblConfigName.setBounds(10, 95, 520, 14);

        btnSelectFile.setText("...");
        btnSelectFile.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSelectFileActionPerformed(evt);
            }
        });
        jPanel2.add(btnSelectFile);
        btnSelectFile.setBounds(144, 155, 31, 20);

        jLabel10.setFont(new java.awt.Font("Tahoma", 1, 11)); // NOI18N
        jLabel10.setText("Errors");
        jPanel2.add(jLabel10);
        jLabel10.setBounds(10, 210, 60, 14);

        scrollErrors.setOpaque(false);
        scrollErrors.setPreferredSize(new java.awt.Dimension(100, 100));

        lstErrors.setForeground(new java.awt.Color(255, 0, 0));
        scrollErrors.setViewportView(lstErrors);

        jPanel2.add(scrollErrors);
        scrollErrors.setBounds(10, 230, 580, 90);

        lblCalIndex.setText("Calibration Index: ?");
        jPanel2.add(lblCalIndex);
        lblCalIndex.setBounds(10, 115, 490, 14);

        txtFileName.setText("<please select a file>");
        jPanel2.add(txtFileName);
        txtFileName.setBounds(10, 155, 128, 20);

        lblCalSN.setText("Calibration Serial#: ?");
        jPanel2.add(lblCalSN);
        lblCalSN.setBounds(10, 135, 480, 14);

        btnClear.setText("Clear Errors");
        btnClear.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnClearPerformed(evt);
            }
        });
        jPanel2.add(btnClear);
        btnClear.setBounds(500, 180, 110, 23);

        jButton1.setText("Bias");
        jButton1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton1ActionPerformed(evt);
            }
        });
        jPanel2.add(jButton1);
        jButton1.setBounds(10, 6, 70, 23);

        lblForceUnits.setText("Force units: ?");
        jPanel2.add(lblForceUnits);
        lblForceUnits.setBounds(10, 35, 470, 14);

        lblTorqueUnits.setText("Torque units: ?");
        jPanel2.add(lblTorqueUnits);
        lblTorqueUnits.setBounds(10, 55, 470, 14);

        lblConfigIndex.setText("Config Index: ?");
        jPanel2.add(lblConfigIndex);
        lblConfigIndex.setBounds(10, 75, 508, 14);

        btnDataPoint.setText("Collect Data Point");
        btnDataPoint.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnDataPoint(evt);
            }
        });
        jPanel2.add(btnDataPoint);
        btnDataPoint.setBounds(10, 180, 140, 23);

        getContentPane().add(jPanel2);
        jPanel2.setBounds(0, 390, 620, 350);

        visualCubePane.setDoubleBuffered(true);
        getContentPane().add(visualCubePane);
        visualCubePane.setBounds(230, 260, 290, 290);

        jMenu2.setText("View");

        standardViewMenuItem.setSelected(true);
        standardViewMenuItem.setText("Standard View");
        standardViewMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                standardViewMenuItemActionPerformed(evt);
            }
        });
        jMenu2.add(standardViewMenuItem);

        historyViewMenuItem.setActionCommand("History View");
        historyViewMenuItem.setLabel("History View");
        historyViewMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                historyViewMenuItemActionPerformed(evt);
            }
        });
        jMenu2.add(historyViewMenuItem);
        jMenu2.add(jSeparator1);

        jCheckBoxAutoScaleHistory.setSelected(true);
        jCheckBoxAutoScaleHistory.setText("Auto Scale History Chart");
        jCheckBoxAutoScaleHistory.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jCheckBoxAutoScaleHistoryActionPerformed(evt);
            }
        });
        jMenu2.add(jCheckBoxAutoScaleHistory);

        jMenuBar1.add(jMenu2);

        menuDataLog.setText("Log Data");
        menuDataLog.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                menuDataLogMouseClicked(evt);
            }
        });
        jMenuBar1.add(menuDataLog);

        jMenu1.setText("Help");
        jMenu1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenu1ActionPerformed(evt);
            }
        });

        jMenuItem1.setText("About...");
        jMenuItem1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMenuItem1ActionPerformed(evt);
            }
        });
        jMenu1.add(jMenuItem1);

        jMenuBar1.add(jMenu1);

        setJMenuBar(jMenuBar1);

        getAccessibleContext().setAccessibleDescription("");

        java.awt.Dimension screenSize = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        setBounds((screenSize.width-636)/2, (screenSize.height-782)/2, 636, 782);
    }// </editor-fold>//GEN-END:initComponents

    private void jMenu1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenu1ActionPerformed
// TODO add your handling code here:
    }//GEN-LAST:event_jMenu1ActionPerformed

    private void jMenuItem1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenuItem1ActionPerformed
        /* Create an "About" box and display it. */
        JFrame fAbout = new JFrame("About ATINetFT Demo");
        URL urlIconPath = getClass().getResource("ati1.jpg"); //ClassLoader.getSystemClassLoader().getSystemResource("ati1.ico");
        Image img = Toolkit.getDefaultToolkit().getImage(urlIconPath);        
        if (null != img)
            fAbout.setIconImage(img);
        fAbout.setLayout(null);       
        final int iLabelXPosition = 10; /* X position of text on about box. */
        addLabelToFrame("<HTML><U>ATI Net F/T Java Software</U>", fAbout, iLabelXPosition, 0, 200, 20);
        
        addLabelToFrame("UI Version " + getVersion(), fAbout, iLabelXPosition, 20, 200, 20);
        addLabelToFrame("Net F/T Interface Version " + NetFTSensor.getVersion(), 
                fAbout, iLabelXPosition, 40, 200, 20);
        addLabelToFrame("Copyright " + COPYRIGHT, fAbout, iLabelXPosition, 80, 200, 20); 
        addLabelToFrame("by ATI Industrial Automation, Inc.", fAbout, iLabelXPosition, 100, 200, 20);
        addLabelToFrame("All rights reserved", fAbout, iLabelXPosition, 120, 200, 20);
        addLabelToFrame("http://www.ati-ia.com", fAbout, iLabelXPosition, 140, 200, 20);
        fAbout.setBounds(100, 100, 260, 200);
        fAbout.setResizable(false);        
        fAbout.setVisible(true);
        
    }//GEN-LAST:event_jMenuItem1ActionPerformed
    
    /**Adds a label to a JFrame's content pane.
     * @param strText   The text of the label to add.
     * @param jfr       The frame to add the label to.
     * @param x         The x position of the Label.
     * @param y         The y position of the label.
     * @length          The length of the label.
     * @height          The height of the label.
     * @return          The Label that was added.
     */
    private JLabel addLabelToFrame(String strText, JFrame jfr, int x, int y, 
            int length, int height)
    {   
        JLabel jlbl = new JLabel(strText);
        addComponentToFrame(jlbl, jfr, x, y, length, height); 
        return jlbl;
        
    }
    
    /**Adds a component to a JFrame's content pane.
     * @param cmp       The component to add.
     * @param jfr       The frame to add the component to.
     * @param x         The x position of the component.
     * @param y         The y position of the component.
     * @param length    The length of the component.
     * @param height    The heigh of the component.
     */
    private void addComponentToFrame(Component cmp, JFrame jfr, int x, int y,
            int length, int height)
    {
        jfr.getContentPane().add(cmp);
        cmp.setBounds(x, y, length, height);        
    }
    private void btnSelectFileActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSelectFileActionPerformed
        /*the file dialog used to choose the file to save data in.*/
        FileDialog cDataFileChooser = new FileDialog( this,
                "Choose File to Save Data to", FileDialog.LOAD );
        cDataFileChooser.setVisible( true );
        if ( null == cDataFileChooser.getFile() ){
            return;
        }
        txtFileName.setText( cDataFileChooser.getDirectory() + cDataFileChooser.getFile() );
    }//GEN-LAST:event_btnSelectFileActionPerformed

    private void formWindowClosing(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowClosing
        stopReaderThread();
        m_cNetFTSlowSocket.close();
        try {
            if ( m_bDoingHighSpeedCollection ){
                m_netFT.stopDataCollection( m_cNetFTFastSocket );
            }                    
        }catch ( IOException ioexc ){
            JOptionPane.showMessageDialog( this, "An IOException has " + 
                    "occurred: " + ioexc.getMessage(), "IOException", 
                    JOptionPane.ERROR_MESSAGE );
        }
        //save settings
        saveSettings();
    }//GEN-LAST:event_formWindowClosing

private void btnClearPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnClearPerformed
// TODO add your handling code here:
    lstErrors.setModel(new DefaultListModel());
}//GEN-LAST:event_btnClearPerformed

private void standardViewMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_standardViewMenuItemActionPerformed
    // TODO add your handling code here:
    standardViewMenuItem.setState(true);
    historyViewMenuItem.setState(false);

    standardPanel.setVisible(true);
    jPanel1.setVisible(false);
    m_demoOptions.setShowHistoryView(false);
    saveSettings();
}//GEN-LAST:event_standardViewMenuItemActionPerformed

private void historyViewMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_historyViewMenuItemActionPerformed
    // TODO add your handling code here:
    standardViewMenuItem.setState(false);
    historyViewMenuItem.setState(true);

    standardPanel.setVisible(false);
    jPanel1.setVisible(true);
    m_demoOptions.setShowHistoryView(true);
    saveSettings();
}//GEN-LAST:event_historyViewMenuItemActionPerformed

private void jButton1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton1ActionPerformed
    try{
        m_netFT.tare();
    }catch(SocketException sexc){
        displayError( "SocketException: " + sexc.getMessage());
    }catch(IOException ioexc){
        displayError("IOException: " + ioexc.getMessage());
    }
}//GEN-LAST:event_jButton1ActionPerformed

private void jButtonApplyHistoryDurationActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonApplyHistoryDurationActionPerformed
    ApplyHistoryDurationChange();
}//GEN-LAST:event_jButtonApplyHistoryDurationActionPerformed

private void jButtonResetGraphActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonResetGraphActionPerformed
    drawingPanel.setArraySize(10*m_demoOptions.getHistoryDuration());
}//GEN-LAST:event_jButtonResetGraphActionPerformed

    private void jCheckBoxAutoScaleHistoryActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jCheckBoxAutoScaleHistoryActionPerformed
        if (jCheckBoxAutoScaleHistory.isSelected()){
            m_demoOptions.setAutoScaleHistory(true);
        }
        else{
            m_demoOptions.setAutoScaleHistory(false);
        }
        saveSettings();
    }//GEN-LAST:event_jCheckBoxAutoScaleHistoryActionPerformed

    private void jTextFieldHistoryDurationKeyTyped(java.awt.event.KeyEvent evt) {//GEN-FIRST:event_jTextFieldHistoryDurationKeyTyped
        String userInput = jTextFieldHistoryDuration.getText().trim();
        //Handle highlighted text
        String selectedText = null;
        
        //Check for enter key
        if(evt.getKeyChar() == '\n'){
            ApplyHistoryDurationChange();
            return;
        }
        
        try{
            //this code only executes if there is any selected text, otherwise an
            //exception will break out
            selectedText = jTextFieldHistoryDuration.getSelectedText().trim();
            if(Character.isDigit(evt.getKeyChar())){
                userInput.replace(selectedText, "" + evt.getKeyChar());
            }
            else userInput.replace(selectedText, "");
            return;
        }
        catch(Exception e){}//this happens when no text is selected, not an issue
        
        //Only allow 3 numeric digits to be entered
        char newChar = evt.getKeyChar();
        if (Character.isDigit(newChar)){
            if(userInput.length() < 3) userInput += newChar;
        }
        jTextFieldHistoryDuration.setText(userInput);
        evt.consume();
    }//GEN-LAST:event_jTextFieldHistoryDurationKeyTyped

    private void formComponentResized(java.awt.event.ComponentEvent evt) {//GEN-FIRST:event_formComponentResized
        //Manual resizing of window components.  I have wasted more time fighting
        //Java's layout managers than it takes to manually resize window components.
        //Some layout managers (Free layout with Netbeans) require a 100kB library,
        //meaning they exceed the size requirement of this Demo.
        if(m_lastWindowWidth != this.getWidth() || m_lastWindowHeight != this.getHeight()){
            int width = m_lastWindowWidth = this.getWidth();
            int height = m_lastWindowHeight = this.getHeight();
            int minWidth = this.getMinimumSize().width;
            int minHeight = this.getMinimumSize().height;
            
            ////TOP PANEL////
            //jPanel3 Sizing
            int jPanel3Width = width;
            int jPanel3Height = 380;
            jPanel3.setBounds(0, 0, jPanel3Width, jPanel3Height);
            //jLayeredPane2 Sizing
            int jLayeredPane2Width = jPanel3Width - 206;
            int jLayeredPane2Height = jPanel3Height - 130;
            jLayeredPane2.setBounds(176, 39, jLayeredPane2Width, jLayeredPane2Height);
            //jHistoryPane Sizing
            int jHistoryPaneWidth = jLayeredPane2Width;
            int jHistoryPaneHeight = jLayeredPane2Height - 47;
            //Set history buffer image size
            bi = gc.createCompatibleImage(jHistoryPaneWidth + 1, jHistoryPaneHeight + 1);
            //standardPanel Sizing
            int standardPanelWidth = jLayeredPane2Width;
            int standardPanelHeight = jLayeredPane2Height - 50;
            standardPanel.setBounds(0, 0, standardPanelWidth, standardPanelHeight);
            //Progress Bar sizing
            int progressBarWidth = standardPanelWidth - 20;
            int progressBarHeight = 18;
            progFx.setBounds(10, 8, progressBarWidth, progressBarHeight);
            progFy.setBounds(10, 38, progressBarWidth, progressBarHeight);
            progFz.setBounds(10, 68, progressBarWidth, progressBarHeight);
            progTx.setBounds(10, 98, progressBarWidth, progressBarHeight);
            progTy.setBounds(10, 128, progressBarWidth, progressBarHeight);
            progTz.setBounds(10, 158, progressBarWidth, progressBarHeight);
            
            ////BOTTOM PANEL////
            //jPanel2 Sizing
            int jPanel2Width = width - 20;
            int jPanel2Height = 380;//420
            jPanel2.setBounds(0, height - 380, jPanel2Width, jPanel2Height);
            //lstErrors Sizing
            int scrollErrorsWidth = jPanel2Width - 15;
            int scrollErrorsHeight = 75;
            scrollErrors.setBounds(10, jPanel2Height - 150, scrollErrorsWidth, scrollErrorsHeight);
            //btnClear Sizing
            int btnClearWidth = 130;
            int btnClearHeight = 23;
            btnClear.setBounds(jPanel2Width - btnClearWidth - 7, 190, btnClearWidth, btnClearHeight);
            
            ////VISUALIZATION CUBE////
            int visCubeNormSize = 330;
            int cubeExpansionSize = cubeExpansionSize = width - minWidth;
            if(height - minHeight < cubeExpansionSize) cubeExpansionSize = height - minHeight;
            visualCubePane.setBounds(180 + (int)((width - minWidth)/3), 240,
                    visCubeNormSize + cubeExpansionSize, visCubeNormSize + cubeExpansionSize);
        }
    }//GEN-LAST:event_formComponentResized

    private void btnDataPoint(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDataPoint
        try{
             m_cDataWriter = new PrintWriter( new FileWriter(
                    txtFileName.getText(), true ) );
             m_recordNextCollection = true;
        }catch ( IOException cIOExc ){
            /*if we can't open the file, don't do data collection*/
            displayError( "IOException: " + cIOExc.getMessage() );
            m_recordNextCollection = false;
        }
    }//GEN-LAST:event_btnDataPoint

    private void btnCollectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCollectActionPerformed
         /*synchronize to avoid stopping the data collection in the middle
         *of trying to read data in the NetFTReaderThread*/
        System.out.println("Collect button pressed.");
        
        synchronized ( m_netFT ){
                      
            /*if we were collecting data, stop it now*/
            if ( m_bDoingHighSpeedCollection ){            
                btnCollect.setText( "Start Collecting" );
                m_bDoingHighSpeedCollection = false;  
                try{
                    m_netFT.stopDataCollection( m_cNetFTFastSocket );
                    m_cDataWriter.close();
                }catch ( IOException cIOexc ){
                    displayError( "IOException: " + cIOexc.getMessage() );
                }
                /*go back to reading single data points in slow mode*/
                //stopAndRestartSingleReadings();
                return;
            }      
            //stopReaderThread();
        }
        
        try{
            m_cDataWriter = new PrintWriter( new FileWriter(
                    txtFileName1.getText() ) );
            Calendar curTime = Calendar.getInstance(); 
            String dateTimeString = DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT ).format( 
                    curTime.getTime() );
            m_cDataWriter.println( "Start Time: " + dateTimeString );
            m_cDataWriter.println( "RDT Sample Rate: " + m_iRDTSampleRate );
            m_cDataWriter.println("\"PLEASE NOTE: The sample rate is read from the Net F/T when the program starts.  If you have changed the sample rate since the program started, this will be incorrect.\"");
            m_cDataWriter.println( lblForceUnits.getText() );
            m_cDataWriter.println( "Counts per Unit Force: " + 
                    m_daftCountsPerUnit[0] );
            m_cDataWriter.println( lblTorqueUnits.getText() );            
            m_cDataWriter.println( "Counts per Unit Torque: " +
                    m_daftCountsPerUnit[3] );
            m_cDataWriter.println( "Status (hex), RDT Sequence, F/T Sequence, Fx, Fy, Fz," +
                    " Tx, Ty, Tz, Time" );
        }catch ( IOException cIOExc ){
            /*if we can't open the file, don't do data collection*/
            displayError( "IOException: " + cIOExc.getMessage() );
            m_bDoingHighSpeedCollection = false;
            //stopAndRestartSingleReadings();
            return;
        }
        
        /*we're starting a new round of high-speed data collection*/
        btnCollect.setText( "Stop Collecting" );
        
        /*set count to infinity - we'll stop collecting when the user
         *presses the "Stop Collecting" button*/        
        try{
            
            m_cNetFTFastSocket = m_netFT.startHighSpeedDataCollection( 
                    0 );
            m_bDoingHighSpeedCollection = true;  
        }catch ( SocketException cSexc ){
            displayError( "SocketException: " + cSexc.getMessage() );
        }catch ( IOException cIOexc ){
            displayError( "IOException" + cIOexc.getMessage() );
        }
        //StartNetFTReaderThread();
    }//GEN-LAST:event_btnCollectActionPerformed

    private void btnSelectFile1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSelectFile1ActionPerformed
        /*the file dialog used to choose the file to save data in.*/
        FileDialog cDataFileChooser = new FileDialog( this,
                "Choose File to Save Data to", FileDialog.SAVE );
        cDataFileChooser.setVisible( true );
        if ( null == cDataFileChooser.getFile() ){
            return;
        }
        txtFileName1.setText( cDataFileChooser.getDirectory() + cDataFileChooser.getFile() );
    }//GEN-LAST:event_btnSelectFile1ActionPerformed

    private void menuDataLogMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_menuDataLogMouseClicked
        if(dialogLogData.isVisible()){
            dialogLogData.setVisible(false);
            return;
        }
        dialogLogData.setVisible(true);
        dialogLogData.setLocation(this.getLocationOnScreen().x,this.getLocationOnScreen().y+50);
        dialogLogData.toFront();
    }//GEN-LAST:event_menuDataLogMouseClicked
    
    /**Starts GUI
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        java.awt.EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                new NetFTDiscoveryGUI().setVisible(true);
            }
        });
    }
    
    /**Updates the error display with a specific error message.
     */
    private class UpdateErrorDisplay implements Runnable{
        private String m_sErrDesc; /*the error description*/
            
        /**displays the error text*/
        @Override
        public void run(){
            Calendar curTime = Calendar.getInstance(); 
            String errString = DateFormat.getTimeInstance( 
                    DateFormat.MEDIUM ).format( curTime.getTime() ) + " - " +
                    m_sErrDesc;
            DefaultListModel errListModel = 
                    (DefaultListModel)lstErrors.getModel(); /*contains the list
                     *of errors*/
            errListModel.add( 0, errString ); 
        }
        
        /**Creates a new UpdateErrorDisplay.
         *@param setErrDesc     The String describing the error
         */
        public UpdateErrorDisplay( String setErrDesc ){
            m_sErrDesc = setErrDesc;
        }
    }
    
    /**Display an error in the last error field, including the time that
     *the error occurred.  Does the displaying using InvokeLater, so it's
     *thread safe.
     *@param errDesc    The String to display in the last error field.  The
     *time will be displayed in front of this text, so there is no need to put
     *the time in yourself.
     */
    public void displayError( String errDesc ){
        Runnable updateDisplay = new UpdateErrorDisplay( errDesc );
        javax.swing.SwingUtilities.invokeLater( updateDisplay );       
    }    
    
    
    
    
    /**Runnable which updates the Force and Torque display using data
     *from an RDT packet.  Used exclusively by the DisplayFTData method.
     */
    private class UpdateFTDisplay implements Runnable{
        
        private NetFTRDTPacket m_rdtPacket;
        
        /**Creates a new UpdateFTDisplay
         *@param setRDTPacket   The NetFTRDTPacket which contains the
         *F/T data to display.
         */
        public UpdateFTDisplay( NetFTRDTPacket setRDTPacket ){
            m_rdtPacket = setRDTPacket;
        }
        
        @Override
        public void run(){
            lblStatus.setText( "0x" + String.format( "%08x",
                (int)m_rdtPacket.getStatus() ) );
            lblRDTSeq.setText( "" + m_rdtPacket.getRDTSequence() );
            lblFTSeq.setText( "" + m_rdtPacket.getFTSequence() );
            int ftCountsReading[] = m_rdtPacket.getFTArray();
            double ftRealReading[] = new double[NUM_FT_AXES];
            int i;
            
            for ( i = 0; i < NUM_FT_AXES; i++)
            {
                /*display the numbers*/
                ftRealReading[i] = ftCountsReading[i] / m_daftCountsPerUnit[i];

                m_lblaFTLabel[i].setText( m_dfReading.format( ftRealReading[i] ) );

                /*standard view mode*/
                if(standardViewMenuItem.getState()){
                    if ( ftRealReading[i] < 0)
                        m_progaFTReadingBar[i].setForeground( NEGATIVE_COLOR );
                    else
                        m_progaFTReadingBar[i].setForeground( POSITIVE_COLOR );
                    m_progaFTReadingBar[i].setValue( (int)Math.abs( ftRealReading[i] /
                        m_daftMaxes[i] * 100 ) );
                }
            }
            /*history view mode*/
            if(historyViewMenuItem.getState()){
                //Get max daft value
                double max = 0;
                for(int j = 0; j < NUM_FT_AXES; j++){
                    if(m_daftMaxes[j] > max) max = m_daftMaxes[j];
                }
                //Autoscaling history
                if(m_demoOptions.isAutoScaleHistory()){
                    drawingPanel.setAutoscaling(true, max);
                }
                else{
                    drawingPanel.setAutoscaling(false, max);
                }
                drawingPanel.addDataPoint(ftRealReading);
                drawingPanel.drawGraphics((JFrame)jPanel3.getTopLevelAncestor()
                        , graphics, g2d, bi, jLayeredPane2.getX() + jPanel3.getX()
                        , jLayeredPane2.getY() + jPanel3.getY() + 50);
            }
            m_ftvc.setFTValues(ftRealReading);
        }
    }
    
    /**Displays new F/T data using InvokeLater, so it's thread-safe
     *@param displayRDT - the NetFTRDTPacket containing the F/T data to
     *display
     */
    public void displayFTData( NetFTRDTPacket displayRDT ){
            UpdateFTDisplay updater = new UpdateFTDisplay( displayRDT );
            javax.swing.SwingUtilities.invokeLater( updater );
    }
    
    /**This method gets the latest force and torque values, and displays
     *them on the screen.
     */
    private void GetFTValues()
    {
        NetFTRDTPacket rdtData;
        try{
            rdtData = m_netFT.readLowSpeedData(m_cNetFTSlowSocket);
        }catch ( SocketException sexc ){
            displayError( "SocketException: " + sexc.getMessage());
        }catch ( IOException iexc ){
            displayError( "IOException: " + iexc.getMessage());
        }
    }
    
    /** Gets the version of the Net F/T sample GUI.
     * @return The version of the GUI.
     */
    public static String getVersion()
    {
        return VERSION;
    }

    private void ApplyHistoryDurationChange(){
        //parse user input for only "000"-"999" digit combinations
        String userInput = jTextFieldHistoryDuration.getText().trim();
        if (!userInput.matches("^([0-9]|[0-9][0-9]|[0-9][0-9][0-9])$")){
            //if an incorrect input is entered, clear the text field
            jTextFieldHistoryDuration.setText("");
        }
        else{
            //parse the valid input to an integer, applying boundaries to the values
            //for performance reasons
            int historyDuration = Integer.parseInt(userInput);
            if(historyDuration < 2) historyDuration = 2;
            else if(historyDuration > m_maxHistoryDuration) historyDuration = m_maxHistoryDuration;
            //apply the history duration change
            jTextFieldHistoryDuration.setText(Integer.toString(historyDuration));
            m_demoOptions.setHistoryDuration(historyDuration);
            drawingPanel.setArraySize(historyDuration*10);
        }
    }

    
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnClear;
    private javax.swing.JButton btnCollect;
    private javax.swing.JButton btnDataPoint;
    private javax.swing.JButton btnSelectFile;
    private javax.swing.JButton btnSelectFile1;
    private javax.swing.JDialog dialogLogData;
    private javax.swing.JCheckBoxMenuItem historyViewMenuItem;
    private javax.swing.JButton jButton1;
    private javax.swing.JButton jButtonApplyHistoryDuration;
    private javax.swing.JButton jButtonResetGraph;
    private javax.swing.JCheckBoxMenuItem jCheckBoxAutoScaleHistory;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel10;
    private javax.swing.JLabel jLabel11;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel8;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JLayeredPane jLayeredPane2;
    private javax.swing.JMenu jMenu1;
    private javax.swing.JMenu jMenu2;
    private javax.swing.JMenuBar jMenuBar1;
    private javax.swing.JMenuItem jMenuItem1;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JPanel jPanel5;
    private javax.swing.JPopupMenu.Separator jSeparator1;
    private javax.swing.JTextField jTextFieldHistoryDuration;
    private javax.swing.JLabel lblCalIndex;
    private javax.swing.JLabel lblCalSN;
    private javax.swing.JLabel lblConfigIndex;
    private javax.swing.JLabel lblConfigName;
    private javax.swing.JLabel lblFTSeq;
    private javax.swing.JLabel lblForceUnits;
    private javax.swing.JLabel lblFx;
    private javax.swing.JLabel lblFy;
    private javax.swing.JLabel lblFz;
    private javax.swing.JLabel lblRDTSeq;
    private javax.swing.JLabel lblStatus;
    private javax.swing.JLabel lblTorqueUnits;
    private javax.swing.JLabel lblTx;
    private javax.swing.JLabel lblTy;
    private javax.swing.JLabel lblTz;
    private javax.swing.JList lstErrors;
    private javax.swing.JMenu menuDataLog;
    private javax.swing.JProgressBar progFx;
    private javax.swing.JProgressBar progFy;
    private javax.swing.JProgressBar progFz;
    private javax.swing.JProgressBar progTx;
    private javax.swing.JProgressBar progTy;
    private javax.swing.JProgressBar progTz;
    private javax.swing.JScrollPane scrollErrors;
    private javax.swing.JPanel standardPanel;
    private javax.swing.JCheckBoxMenuItem standardViewMenuItem;
    private javax.swing.JTextField txtFileName;
    private javax.swing.JTextField txtFileName1;
    private javax.swing.JLayeredPane visualCubePane;
    // End of variables declaration//GEN-END:variables
    
}
