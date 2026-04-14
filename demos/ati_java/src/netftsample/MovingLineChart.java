/*
 * MovingLineChart.java
 *
 * Created June 2012
 *
 */

package netftsample;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import javax.swing.JFrame;
import javax.swing.JPanel;

/**
 *
 * @author fleda
 */
public class MovingLineChart extends JPanel{
    
    //Field 1
    //FX, FY, FZ, TX, TY, TZ (0-5)
    //Field 2
    //XY data point
    //These are the real measured values that have been passed in.
    private double m_FTYValues[][] = null;
    //These are the calculated window coordinates for drawing lines.
    private int m_FTXCoords[] = null;
    private int m_FTYCoords[][] = null;
    //Pointer for the circular buffer
    private int m_dataBufferPointer = 0;
    
    
    private boolean m_dataHasBeenSet = false;
    
    private int m_xMinPos = 0;
    private int m_yMinPos = 0;
    private int m_xMaxPos = 0;
    private int m_yMaxPos = 0;
    private int m_yZeroPos = 0;
    private double m_maxYValue = 100;
    private boolean m_autoscaling = false;
    private double m_autoScalingMaxValue = 0;
    
    private static final int NUM_XAXIS_TICKS = 10;
    private static final int NUM_YAXIS_TICKS = 10;//use an even number here
    
    public MovingLineChart(){
        
    }
    
    public MovingLineChart(JFrame window, Graphics graphics, Graphics2D g2d, BufferedImage bi){
        this();
        setGraphingArea(window, graphics, g2d, bi);
    }
    
    public void drawGraphics(JFrame window, Graphics graphics, Graphics2D g2d, BufferedImage bi, int xPos, int yPos){
        //check whether the data arrays have been initialized
        if(m_dataHasBeenSet){
            int i = 0, j = 0;
            
            //draw graphics using one background buffer
            window.createBufferStrategy(1);
            BufferStrategy buffer = window.getBufferStrategy();
            
            //auto scaling
            if(this.m_autoscaling == true){
                //find maximum Y Value
                findMaxYValue();
                if(m_maxYValue < (m_autoScalingMaxValue/1000)) m_maxYValue = (m_autoScalingMaxValue/1000);
            }
            calculateYCoords();
            //Resize the graphing area
            setGraphingArea(window, graphics, g2d, bi);
            calculateXCoords();
            
            try{
                //clear back buffer
                g2d = bi.createGraphics();
                
                ////////////////GRAPHICS TO RENDER////////////////
                //Draw background
                g2d.setColor(Color.LIGHT_GRAY);
                g2d.fillRect(0, 0, bi.getWidth() - 1, bi.getHeight() - 1);
                
                //Draw all data points from oldest to newest
                int tempBufferPointer = m_dataBufferPointer;
                for(i = 0; i < 6; i++){
                    j = 0;
                    if(++tempBufferPointer > m_FTYValues[i].length - 1)
                        tempBufferPointer = 0;
                    while(true){
                        //Draw connecting line between two points
                        setColor(i, g2d);
                        if(j < m_FTYValues[i].length - 2){
                            if(tempBufferPointer < m_FTYValues[i].length - 1){
                                g2d.drawLine(m_FTXCoords[j], m_FTYCoords[i][tempBufferPointer],
                                        m_FTXCoords[j+1], m_FTYCoords[i][tempBufferPointer+1]);
                            }
                            else{
                                g2d.drawLine(m_FTXCoords[j], m_FTYCoords[i][tempBufferPointer],
                                        m_FTXCoords[j+1], m_FTYCoords[i][0]);
                            }
                        }
                        //rotate buffer pointer at end of array
                        if(++tempBufferPointer > (m_FTYValues[i].length - 1)) tempBufferPointer = 0;
                        if(++j > (m_FTYValues[i].length - 1)) j = 0;
                        //break of out loop when buffer pointer rotates around fully.
                        if(tempBufferPointer == m_dataBufferPointer)break;
                    }
                }
                
                //Draw Axes, this is drawn after the lines so it is not overwritten
                g2d.setColor(Color.BLACK);
                g2d.drawLine(m_xMinPos, m_yMaxPos, m_xMinPos, m_yMinPos);//y-axis
                g2d.drawLine(m_xMinPos, m_yZeroPos, m_xMaxPos, m_yZeroPos);//x-axis
                
                //Draw x-axis ticks
                for(i = 0; i <= NUM_XAXIS_TICKS; i++){
                    int xtickPosition = m_xMinPos + (int)((double)i*(Math.abs((double)m_xMaxPos - (double)m_xMinPos)/(double)NUM_XAXIS_TICKS));
                    g2d.drawLine(xtickPosition, (m_yZeroPos + 5), xtickPosition, (m_yZeroPos - 5));
                }
                
                //Draw y-axis ticks and their labels
                for(i = 0; i <= NUM_YAXIS_TICKS; i++){
                    int ytickPosition = m_yMinPos - (int)((double)i*(Math.abs((double)m_yMaxPos - (double)m_yMinPos)/(double)NUM_YAXIS_TICKS));
                    if(i == NUM_YAXIS_TICKS/2) ytickPosition = m_yZeroPos;
                    g2d.drawLine(m_xMinPos - 5, ytickPosition, m_xMinPos + 5, ytickPosition);
                    String yLabel = formatYAxisDigit(m_maxYValue, i);
                    int yLabelXPos = m_xMinPos - (6+7*yLabel.length());
                    if( i < (NUM_YAXIS_TICKS / 2)){//Negative values
                        yLabelXPos += 3;
                    }
                    double yValue = m_maxYValue * (((double)(i-(double)NUM_YAXIS_TICKS/2))/5.0);
                    if(((Math.abs(yValue) < 0.0010) || (Math.abs(yValue) > 99999)) && (yValue != 0)){
                        yLabelXPos += 3;
                    }
                    g2d.drawString(yLabel, yLabelXPos, ytickPosition + 5);
                }
                
                //Draw border on top and left
                g2d.drawLine(0, 0, bi.getWidth(), 0);//y-axis
                g2d.drawLine(0, 0, 0, bi.getHeight());//x-axis
                
                //Draw the Legend
                for(i = 0; i <= 5; i++){
                    int legendLabelPosition = (m_yMaxPos + 26) + (int)((double)i*(((Math.abs((double)m_yMaxPos - (double)m_yMinPos))-50)/5.0));
                    String legendLabels[] = {"Fx","Fy","Fz","Tx","Ty","Tz"};
                    g2d.setColor(Color.BLACK);
                    g2d.drawString(legendLabels[i], m_xMaxPos + 35, legendLabelPosition + 5);
                    setColor(i, g2d);
                    g2d.drawLine(m_xMaxPos + 10, legendLabelPosition, m_xMaxPos + 30, legendLabelPosition);
                }
                //////////////////////////////////////////////////
                
                //blit image and flip
                graphics = buffer.getDrawGraphics();
                graphics.drawImage(bi, xPos, yPos, window);
                if(!buffer.contentsLost())buffer.show();
            }finally {
                // release resources if try fails
                if(graphics!=null) graphics.dispose();
                if(g2d!=null)g2d.dispose();
            }
        }
    }
    
    public void ClearData(){
        //Clear all data arrays
        int i,j;
        if(m_dataHasBeenSet){
            for(i = 0; i < 6; i++){
                for(j = 0; j < m_FTYValues[0].length; j++){
                    m_FTYValues[i][j] = 0;
                    m_FTXCoords[j] = 0;
                    m_FTYCoords[i][j] = m_yZeroPos;
                }
            }
        }
    }
    
    public void addDataPoint(double newValues[]){
        if(m_xMinPos != 0){
            //Add one new data point while removing the oldest one.
            //uses a circular buffer to hold data values
            if(m_dataHasBeenSet){
                for(int i = 0; i < 6; i++){
                    //Read in new values
                    m_FTYValues[i][m_dataBufferPointer] = -newValues[i];
                    //convert new value to coordinate
                    m_FTYCoords[i][m_dataBufferPointer] =
                            (int)(m_yZeroPos + (m_FTYValues[i][m_dataBufferPointer]*
                            ((double)(Math.abs(m_yMaxPos - m_yZeroPos))/(m_maxYValue/2))));
                    
                }
                //increment buffer pointer
                m_dataBufferPointer++;
                if(m_dataBufferPointer > (m_FTYValues[0].length - 1)) m_dataBufferPointer = 0;
            }
        }
    }
    
    public void setArraySize(int size){
        //Creates new arrays of set size and initializes them to 0
        m_FTYValues = new double[6][size];
        m_FTXCoords = new int[size];
        m_FTYCoords = new int[6][size];
        m_dataHasBeenSet = true;
        m_dataBufferPointer = 0;
        ClearData();
        calculateXCoords();
    }
    
    private void setGraphingArea(JFrame window, Graphics graphics, Graphics2D g2d, BufferedImage bi){
        //Set coordinate information
        int maxStringLength = 0;
        for(int i = 0; i < 6; i++){
            String labelString = formatYAxisDigit(m_maxYValue, i);
            if (labelString.length() > maxStringLength) maxStringLength = labelString.length();
        }
        //m_xMinPos = 30 + 7*maxStringLength;
        m_xMinPos = 60;
        m_yMinPos = bi.getHeight() - 10;
        m_xMaxPos = bi.getWidth() - 55;
        m_yMaxPos = 10;
        //calculate position of x axis
        m_yZeroPos = (int)((Math.abs(m_yMaxPos - m_yMinPos)/2) + m_yMaxPos);
    }
    
    private void calculateXCoords(){
        int i;
        for(i = 0; i < m_FTYValues[0].length; i++){
            m_FTXCoords[i] = (int)((double)m_xMinPos + ((double)i)*
                    (((double)(m_xMaxPos - m_xMinPos))/((double)m_FTYValues[0].length)));
        }
    }
    
    private void calculateYCoords(){
        int i,j;
        for(i = 0; i < 6; i++){
            for(j = 0; j < m_FTYValues[i].length; j++){
                        m_FTYCoords[i][j] = (int)(m_yZeroPos + (m_FTYValues[i][j]*((double)
                                (Math.abs(m_yMaxPos - m_yZeroPos))/m_maxYValue)));
            }
        }
    }
    
    private void findMaxYValue(){
        int i,j;
        double max = 0;
        for(i = 0; i < 6; i++){
            for(j = 0; j < m_FTYValues[i].length; j++){
                if(Math.abs(m_FTYValues[i][j]) > Math.abs(max)) max = Math.abs(m_FTYValues[i][j]);
            }
        }
        m_maxYValue = max;
    }
    
    private void setColor(int color, Graphics2D g2d){
        switch(color){
            case 0:
                g2d.setColor(Color.RED);
                break;
            case 1:
                g2d.setColor(Color.BLUE);
                break;
            case 2:
                g2d.setColor(Color.GREEN);
                break;
            case 3:
                g2d.setColor(Color.MAGENTA);
                break;
            case 4:
                g2d.setColor(Color.ORANGE);
                break;
            case 5:
                g2d.setColor(Color.DARK_GRAY);
                break;
            default:
                g2d.setColor(Color.BLACK);//this really isnt needed
                break;
        }
    }
    
    public boolean isAutoscaling() {
        return m_autoscaling;
    }
    
    public void setAutoscaling(boolean autoscaling, double maxValue) {
        //set autoscaling on or off
        if(autoscaling == true){
            findMaxYValue();
            if(m_maxYValue < (maxValue / 1000)) m_maxYValue = (maxValue / 1000);
            m_autoScalingMaxValue = maxValue;
            calculateYCoords();
        }
        else{
            m_maxYValue = maxValue;
            calculateYCoords();
        }
        this.m_autoscaling = autoscaling;
    }
    
    private String formatYAxisDigit(double maxValue, int i){
        double yValue = maxValue*(((double)(i-(double)NUM_YAXIS_TICKS/2))/5.0);
        String yLabel = null;
        NumberFormat formatter = new DecimalFormat("0.##E0");
        
        if(((Math.abs(yValue) < 0.0010) || (Math.abs(yValue) >= 100000)) && (yValue != 0)){
            yLabel = formatter.format(yValue);
            return yLabel;
        }
        
        else if((Math.abs(yValue) >= 10000) && (Math.abs(yValue) < 100000)){
            yLabel = Double.toString(yValue);
            if(yValue < 0){
                if(yLabel.length() > 6) yLabel = yLabel.substring(0, 6);
            }
            else{
                if(yLabel.length() > 5) yLabel = yLabel.substring(0, 5);
            }
            return yLabel;
        }
        
        else{
            yLabel = Double.toString(yValue);
            if(yValue < 0){
                if(yLabel.length() > 7) yLabel = yLabel.substring(0, 7);
            }
            else{
                if(yLabel.length() > 6) yLabel = yLabel.substring(0, 6);
            }
            return yLabel;
        }
        /*else{
            BigDecimal test;
            int newScale = 4-bd.precision()+bd.scale();
            BigDecimal bd2 = bd1.setScale(newScale, RoundingMode.HALF_UP);
            return yLabel;
        }*/
        
        /*else if(Math.abs(yValue) >= 10000){
            if(yValue < 0){
                yLabel = Double.toString(yValue);
                if(yLabel.length() > 7) yLabel = yLabel.substring(0, 6);
            }
            else{
                yLabel = Double.toString(yValue);
                if(yLabel.length() > 6) yLabel = yLabel.substring(0, 5);
            }
            return yLabel;
        }
        
        else if(Math.abs(yValue) >= 1000){
            if(yValue < 0){
                yLabel = Double.toString(yValue);
                if(yLabel.length() > 6) yLabel = yLabel.substring(0, 5);
            }
            else{
                yLabel = Double.toString(yValue);
                if(yLabel.length() > 5) yLabel = yLabel.substring(0, 4);
            }
            return yLabel;
        }
        
        else if(Math.abs(yValue) >= 100){
            if(yValue < 0){
                yLabel = Double.toString(yValue);
                if(yLabel.length() > 4) yLabel = yLabel.substring(0, 4);
            }
            else{
                yLabel = Double.toString(yValue);
                if(yLabel.length() > 3) yLabel = yLabel.substring(0, 3);
            }
            return yLabel;
        }
        
        else{
            if(yValue < 0){
                yLabel = Double.toString(yValue);
                if(yLabel.length() > 6) yLabel = yLabel.substring(0, 6);
            }
            else{
                yLabel = Double.toString(yValue);
                if(yLabel.length() > 5) yLabel = yLabel.substring(0, 5);
            }
            return yLabel;
        }*/
    }
}
