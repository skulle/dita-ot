/*
 * This file is part of the DITA Open Toolkit project hosted on
 * Sourceforge.net. See the accompanying license.txt file for 
 * applicable licenses.
 */

/*
 * (c) Copyright IBM Corp. 2004, 2005 All Rights Reserved.
 */
package org.dita.dost.writer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Stack;

import org.xml.sax.helpers.AttributesImpl;

import org.dita.dost.exception.DITAOTXMLErrorHandler;
import org.dita.dost.log.DITAOTJavaLogger;
import org.dita.dost.log.MessageUtils;
import org.dita.dost.module.Content;
import org.dita.dost.reader.AbstractXMLReader;
import org.dita.dost.reader.GrammarPoolManager;
import org.dita.dost.util.CatalogUtils;
import org.dita.dost.util.Constants;
import org.dita.dost.util.DITAAttrUtils;
import org.dita.dost.util.DelayConrefUtils;
import org.dita.dost.util.FileUtils;
import org.dita.dost.util.FilterUtils;
import org.dita.dost.util.OutputUtils;
import org.dita.dost.util.StringUtils;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;



/**
 * DitaWriter reads dita topic file and insert debug information and filter out the
 * content that is not necessary in the output.
 * 
 * @author Zhang, Yuan Peng
 */
public class DitaWriter extends AbstractXMLWriter {

    private static final String COLUMN_NAME_COL = "col";
    private static final String OS_NAME_WINDOWS = "windows";
    private static final String PI_PATH2PROJ_TARGET = "path2project";
    private static final String PI_WORKDIR_TARGET = "workdir";
    //To check the URL of href in topicref attribute
    private static final String NOT_LOCAL_URL="://";
    //To check whether the attribute of XTRC and XTRF have existed
    private static final String ATTRIBUTE_XTRC = "xtrc";
    private static final String ATTRIBUTE_XTRF = "xtrf";
    
  //Added on 2010-08-24 for bug:3086552 start
    private boolean setSystemid = true;
  //Added on 2010-08-24 for bug:3086552 end
    
    private static boolean checkDITAHREF(Attributes atts){
    	final String classValue = atts.getValue(Constants.ATTRIBUTE_NAME_CLASS);
    	String scopeValue = atts.getValue(Constants.ATTRIBUTE_NAME_SCOPE);
    	String formatValue = atts.getValue(Constants.ATTRIBUTE_NAME_FORMAT);
    	
    	
    	
    	if (classValue == null
    			|| (classValue.indexOf(Constants.ATTR_CLASS_VALUE_XREF) == -1
    			&& classValue.indexOf(Constants.ATTR_CLASS_VALUE_LINK) == -1
    			&& classValue.indexOf(Constants.ATTR_CLASS_VALUE_TOPICREF) == -1))
    	{
    		return false;
    	} 
    	
    	if (scopeValue == null){
    		scopeValue = Constants.ATTR_SCOPE_VALUE_LOCAL;
    	}
    	if (formatValue == null){
    		formatValue = Constants.ATTR_FORMAT_VALUE_DITA;
    	}
    	
    	if (scopeValue.equalsIgnoreCase(Constants.ATTR_SCOPE_VALUE_LOCAL)
    			&& formatValue.equalsIgnoreCase(Constants.ATTR_FORMAT_VALUE_DITA)){
    		return true;
    	}
    	
    	return false;
    }
    
    private static String replaceCONREF (Attributes atts){
    	
    	/*
         * replace all the backslash with slash in 
         * all href and conref attribute
         */
        String attValue = atts.getValue(Constants.ATTRIBUTE_NAME_CONREF);
        final int sharp_index = attValue.lastIndexOf(Constants.SHARP);
        final int dot_index = attValue.lastIndexOf(Constants.DOT);
        if(sharp_index != -1 && dot_index < sharp_index){
        	final String path = attValue.substring(0, sharp_index);
        	final String topic = attValue.substring(sharp_index);
        	if(!path.equals(Constants.STRING_EMPTY)){
        		String relativePath;
        		final File target = new File(path);
        		if(target.isAbsolute()){
        			relativePath = FileUtils.getRelativePathFromMap(OutputUtils.getInputMapPathName(), path);
            		attValue = relativePath + topic;
        		}

        	}
        }else{
        	final File target = new File(attValue);
        	if(target.isAbsolute()){
        		attValue = FileUtils.getRelativePathFromMap(OutputUtils.getInputMapPathName(), attValue);
        	}
        }
        if (attValue != null){
        	attValue = attValue.replaceAll(Constants.DOUBLE_BACK_SLASH, Constants.SLASH);
        }
        
        if(attValue.indexOf(Constants.FILE_EXTENSION_DITAMAP) == -1){
        	return FileUtils.replaceExtName(attValue, extName);
        }

    	return attValue;
    }
    private static boolean notLocalURL(String valueOfURL){
    	if(valueOfURL.indexOf(NOT_LOCAL_URL)==-1) {
            return false;
        } else {
            return true;
        }
    }
    private  static boolean warnOfNoneTopicFormat(Attributes attrs,String valueOfHref){
    	final String hrefValue=valueOfHref;
    	final String formatValue=attrs.getValue(Constants.ATTRIBUTE_NAME_FORMAT);
    	final String classValue=attrs.getValue(Constants.ATTRIBUTE_NAME_CLASS);
    	final String extOfHref=getExtName(valueOfHref);
    	final DITAOTJavaLogger logger=new DITAOTJavaLogger();
		final Properties params = new Properties();
		params.put("%1", hrefValue);	
		if(notLocalURL(hrefValue)){
			return true;
		}
		else{
			if(classValue!=null && classValue.contains(Constants.ATTR_CLASS_VALUE_CODEREF)){
				return true;
			}
			if(formatValue==null && extOfHref!=null && !extOfHref.equalsIgnoreCase("DITA") && !extOfHref.equalsIgnoreCase("XML") ){
				logger.logError(MessageUtils.getMessage("DOTJ028E", params).toString());
				return true;
			}
		}
			
		return false;
    }
    /**
     * Get file extension name.
     * @param attValue file name
     * @return extension name
     */
    public static String getExtName(String attValue){
    	String fileName;
        int fileExtIndex;
        int index;
    	
    	index = attValue.indexOf(Constants.SHARP);
		
    	if (attValue.startsWith(Constants.SHARP)){
    		return null;
    	} else if (index != -1){
    		fileName = attValue.substring(0,index); 
    		fileExtIndex = fileName.lastIndexOf(Constants.DOT);
    		return (fileExtIndex != -1)
    			? fileName.substring(fileExtIndex+1, fileName.length())
    			: null;
    	} else {
    		fileExtIndex = attValue.lastIndexOf(Constants.DOT);
    		return (fileExtIndex != -1)
    			? attValue.substring(fileExtIndex+1, attValue.length())
    			: null;
    	}
    }
	private static String replaceHREF (String attName, Attributes atts){
    	
    	String attValue = null;
    	
    	if (attName == null){
    		return null;
    	}
    	
    	attValue = atts.getValue(attName);
        if(attValue!=null){
        	final int dot_index = attValue.lastIndexOf(Constants.DOT);
            final int sharp_index = attValue.lastIndexOf(Constants.SHARP);
            if(sharp_index != -1 && dot_index < sharp_index){
            	String path = attValue.substring(0, sharp_index);
            	final String topic = attValue.substring(sharp_index);
            	if(!path.equals(Constants.STRING_EMPTY)){
            		String relativePath;
            		//Added by William on 2010-01-05 for bug:2926417 start
            		if(path.startsWith("file:/") && path.indexOf("file://") == -1){
            			path = path.substring("file:/".length());
            			//Unix like OS
                		if(Constants.SLASH.equals(File.separator)){
                			path = Constants.SLASH + path;
            			}
            		}
            		//Added by William on 2010-01-05 for bug:2926417 end
            		final File target = new File(path);
            		if(target.isAbsolute()){
            			relativePath = FileUtils.getRelativePathFromMap(OutputUtils.getInputMapPathName(), path);
                		attValue = relativePath + topic;
            		}

            	}
            }else{
            	//Added by William on 2010-01-05 for bug:2926417 start
        		if(attValue.startsWith("file:/") && attValue.indexOf("file://") == -1){
        			attValue = attValue.substring("file:/".length());
        			//Unix like OS
            		if(Constants.SLASH.equals(File.separator)){
            			attValue = Constants.SLASH + attValue;
        			}
        		}
        		//Added by William on 2010-01-05 for bug:2926417 end
            	final File target = new File(attValue);
            	if(target.isAbsolute()){
            		attValue = FileUtils.getRelativePathFromMap(OutputUtils.getInputMapPathName(), attValue);
            	}
            }    	

    		/*
             * replace all the backslash with slash in 
             * all href and conref attribute
             */     
    		attValue = attValue.replaceAll(Constants.DOUBLE_BACK_SLASH, Constants.SLASH);
    	} else {
    		return null;
    	}
    	
    	if(checkDITAHREF(atts)){
    		if(warnOfNoneTopicFormat(atts,attValue)==false){
    			return FileUtils.replaceExtName(attValue, extName);
    		}
    		
        }
    	
    	return attValue;
    }
    private String absolutePath;
    private HashMap<String, String> catalogMap; //map that contains the information from XML Catalog
    private List<String> colSpec;
    private int columnNumber; // columnNumber is used to adjust column name
    private int columnNumberEnd; //columnNumberEnd is the end value for current entry
    //Added by William on 2009-11-27 for bug:1846993 embedded table bug start
    //stack to store colspec list
    private final Stack<List<String>> colSpecStack; 
    //Added by William on 2009-11-27 for bug:1846993 embedded table bug end
    
    //Added by William on 2010-07-01 for bug:3023642 start
    //stack to store rowNum
    private final Stack<Integer> rowNumStack;
    //stack to store columnNumber
    private final Stack<Integer> columnNumberStack;
    //stack to store columnNumberEnd
    private final Stack<Integer> columnNumberEndStack;
    //stack to store rowsMap
    private final Stack<Map<String, Integer>> rowsMapStack;
    //Added by William on 2010-07-01 for bug:3023642 end
    
    
    //Added by William on 2009-06-30 for colname bug:2811358 start
    //store row number
    private int rowNumber;
    //store total column count
    private int totalColumns;
    //store morerows attribute
    private Map<String, Integer> rowsMap;
    //Added by William on 2009-06-30 for colname bug:2811358 end
    //Added by William on 2009-07-18 for req #12014 start
    //transtype
    private String transtype;
    //Added by William on 2009-07-18 for req #12014 start
    
    private HashMap<String, Integer> counterMap;
    private boolean exclude; // when exclude is true the tag will be excluded.
    private int foreignLevel; // foreign/unknown nesting level
    private int level;// level is used to count the element level in the filtering
    private final DITAOTJavaLogger logger;
    private boolean needResolveEntity; //check whether the entity need resolve.
    private OutputStreamWriter output;
    private String path2Project;
    private String props; // contains the attribution specialization from props
    
    private String tempDir;
    private String traceFilename;
    private boolean insideCDATA;

    private Map<String, String> keys = null;
    
    //Added by William on 2010-02-25 for bug:2957456 start
    private String inputFile = null;
    //Added by William on 2010-02-25 for bug:2957456 end
    
    //Added by William on 2010-06-01 for bug:3005748 start
    //Get DITAAttrUtil
    private final DITAAttrUtils ditaAttrUtils = DITAAttrUtils.getInstance();
    //Added by William on 2010-06-01 for bug:3005748 end
    
    private HashMap<String, HashMap<String, HashSet<String>>> validateMap = null;
	private HashMap<String, HashMap<String, String>> defaultValueMap = null;
    
    /** XMLReader instance for parsing dita file */
    private XMLReader reader = null;
    /**
     * Default constructor of DitaWriter class.
     * 
     * {@link #initXMLReader(String, boolean, boolean)} must be called after
     * construction to initialize XML parser.
     */
    public DitaWriter() {
        super();
        exclude = false;
        columnNumber = 1;
        columnNumberEnd = 0;
        //Added by William on 2009-06-30 for colname bug:2811358 start
        //initialize row number
        rowNumber = 0;
        //initialize total column count
        totalColumns = 0;
        //initialize the map
        rowsMap = new HashMap<String, Integer>();
        //Added by William on 2009-06-30 for colname bug:2811358 start
        catalogMap = CatalogUtils.getCatalog(null);
        absolutePath = null;
        path2Project = null;
        counterMap = null;
        traceFilename = null;
        foreignLevel = 0;
        level = 0;
        needResolveEntity = false;
        insideCDATA = false;
        output = null;
        tempDir = null;
        colSpec = null;
        //initial the stack
        colSpecStack = new Stack<List<String>>();
        //added by William on 20100701 for bug:3023642 start
        rowNumStack = new Stack<Integer>();
        columnNumberStack = new Stack<Integer>();
        columnNumberEndStack = new Stack<Integer>();
        rowsMapStack = new Stack<Map<String,Integer>>();
        //added by William on 20100701 for bug:3023642 end
        
        props = null;
        validateMap = null;
        logger = new DITAOTJavaLogger();
    }

    /**
     * Initialize XML reader used for pipeline parsing.
     * @param ditaDir ditaDir
     * @param validate whether validate
     * @throws SAXException SAXException
     */
	public void initXMLReader(final String ditaDir, final boolean validate, final boolean arg_setSystemid) throws SAXException {
		try {
			reader = StringUtils.getXMLReader();
			AbstractXMLReader.setGrammarPool(reader, null);
 			
            reader.setProperty(Constants.LEXICAL_HANDLER_PROPERTY,this);
            reader.setFeature("http://apache.org/xml/features/scanner/notify-char-refs", true);
            reader.setFeature("http://apache.org/xml/features/scanner/notify-builtin-refs", true);
            reader.setFeature(Constants.FEATURE_NAMESPACE_PREFIX, true);
            if(validate==true){
            	reader.setFeature(Constants.FEATURE_VALIDATION, true);
            	reader.setFeature(Constants.FEATURE_VALIDATION_SCHEMA, true);
            }
			reader.setFeature(Constants.FEATURE_NAMESPACE, true);
	        reader.setContentHandler(this);
	        reader.setEntityResolver(CatalogUtils.getCatalogResolver());
		} catch (final Exception e) {
			throw new SAXException("Failed to initialize XML parser: " + e.getMessage(), e);
		}
		AbstractXMLReader.setGrammarPool(reader, GrammarPoolManager.getGrammarPool());
		CatalogUtils.setDitaDir(ditaDir);
		catalogMap = CatalogUtils.getCatalog(ditaDir);
		setSystemid= arg_setSystemid;
	}
    
	@Override
    public void characters(char[] ch, int start, int length)
            throws SAXException {
        if (!exclude && needResolveEntity) { 
        	// exclude shows whether it's excluded by filtering
        	// isEntity shows whether it's an entity.
            try {
            	if(insideCDATA) {
                    output.write(ch, start, length);
                } else {
                    output.write(StringUtils.escapeXML(ch,start, length));
                }
            } catch (final Exception e) {
            	logger.logException(e);
            }
        }
    }
    
    /**
     * Write attribute to output.
     * 
	 * @param attQName attribute name
	 * @param attValue attribute value that has been XML escaped
	 * @throws IOException if writing to output failed
	 */
    private void copyAttribute(String attQName, String attValue) throws IOException{
    	output.write(Constants.STRING_BLANK);
		output.write(attQName);
		output.write(Constants.EQUAL);
		output.write(Constants.QUOTATION);
		output.write(attValue);
		output.write(Constants.QUOTATION);
    }
        
    /**
     * Process attribute values and output attributes.
     * 
     * @param qName current element qName
	 * @param atts attributes of current element
	 * @throws IOException
	 */
	private void copyElementAttribute(String qName, AttributesImpl atts) throws IOException {
		// copy the element's attributes    
		final int attsLen = atts.getLength();
		 boolean conkeyrefValid = false;
		for (int i = 0; i < attsLen; i++) {
		    final String attQName = atts.getQName(i);
		    String attValue = atts.getValue(i);
		    final String nsUri = atts.getURI(i);
		    
		  //Probe for default values
			if (StringUtils.isEmptyString(attValue) && this.defaultValueMap != null) {
				final HashMap<String, String> defaultMap = this.defaultValueMap.get(attQName);
				if (defaultMap != null) {
					final String defaultValue = defaultMap.get(qName);
					if (defaultValue != null) {
						attValue = defaultValue;
					}
				}
			}
		    
		    if(Constants.ATTRIBUTE_NAME_HREF.equals(attQName)
		    		|| Constants.ATTRIBUTE_NAME_COPY_TO.equals(attQName)){
		        if(atts.getValue(Constants.ATTRIBUTE_NAME_SCOPE)!=null && (atts.getValue(Constants.ATTRIBUTE_NAME_SCOPE).equalsIgnoreCase("external")||atts.getValue(Constants.ATTRIBUTE_NAME_SCOPE).equalsIgnoreCase("peer"))){
		        	attValue = atts.getValue(i);		        	
		        }else{
		        	attValue = replaceHREF(attQName, atts);
		        	//added on 2010-09-02 for bug:3058124(decode escaped string)
		        	attValue = URLDecoder.decode(attValue, Constants.UTF8);
		        }
		        
		    } else if (Constants.ATTRIBUTE_NAME_CONREF.equals(attQName)){
		                                    
		        attValue = replaceCONREF(atts);
		        //added on 2010-09-02 for bug:3058124(decode escaped string)
		        attValue = URLDecoder.decode(attValue, Constants.UTF8);
		    } else {
		        attValue = atts.getValue(i);
		    }

		    if (Constants.ATTRIBUTE_NAME_DITAARCHVERSION.equals(attQName)){
		    	final String attName = Constants.ATTRIBUTE_PREFIX_DITAARCHVERSION + Constants.COLON + attQName;
		    	
		    	copyAttribute(attName, attValue);
		    	copyAttribute(Constants.ATTRIBUTE_NAMESPACE_PREFIX_DITAARCHVERSION, nsUri);
		    	
		    }
		    
		    // replace conref with conkeyref(using key definition)
		    if(Constants.ATTRIBUTE_NAME_CONKEYREF.equals(attQName) && !attValue.equals(Constants.STRING_EMPTY)){
		    	final int sharpIndex = attValue.indexOf(Constants.SHARP);
		    	final int slashIndex = attValue.indexOf(Constants.SLASH);
		    	int keyIndex = -1;
		    	if(sharpIndex != -1){
		    		keyIndex = sharpIndex;
		    	}else if(slashIndex != -1){
		    		keyIndex = slashIndex;
		    	}
		    	//conkeyref only accept values such as "key" or "key/id"
		    	//bug:3081597
		    	if(sharpIndex == -1){
			    	if(keyIndex != -1){
			    		//get keyref value
			    		final String key = attValue.substring(0,keyIndex);
			    		String target;
			    		if(!key.equals(Constants.STRING_EMPTY) && keys.containsKey(key)){
			    			
			    			//target = FileUtils.replaceExtName(target);
			    			//Added by William on 2009-06-25 for #12014 start
			    			//get key's href
			    			final String value = keys.get(key);
			    			final String href = value.substring(0, value.lastIndexOf(Constants.LEFT_BRACKET));
			    			
			    			//Added by William on 2010-02-25 for bug:2957456 start
			    			final String updatedHref = updateHref(href);
			    			//Added by William on 2010-02-25 for bug:2957456 end
				    		
			    			//get element/topic id
				    		final String id = attValue.substring(keyIndex+1);
				    		
				    		boolean idExported = false;
				    		boolean keyrefExported = false;
				    		List<Boolean> list = null;
				    		if(transtype.equals(Constants.INDEX_TYPE_ECLIPSEHELP)){
					    		 list = DelayConrefUtils.getInstance().checkExport(href, id, key, tempDir);
					    		 idExported = list.get(0).booleanValue();
					    		 keyrefExported = list.get(1).booleanValue();
				    		}
				    		//both id and key are exported and transtype is eclipsehelp
			    			if(idExported && keyrefExported 
			    					&& transtype.equals(Constants.INDEX_TYPE_ECLIPSEHELP)){
			    			//remain the conkeyref attribute.
			    				copyAttribute("conkeyref", attValue);
			    			//Added by William on 2009-06-25 for #12014 end
			    			}else {
			    				//normal process
			    				target = updatedHref;
			    				//Added by William on 2010-05-17 for conkeyrefbug:3001705 start
			    				//only replace extension name of topic files.
			    				target = replaceExtName(target);
				    			//Added by William on 2010-05-17 for conkeyrefbug:3001705 end
				    			String tail ;
				    			if(sharpIndex == -1 ){
				    				if(target.indexOf(Constants.SHARP) == -1) {
                                        //change to topic id
				    					tail = attValue.substring(keyIndex).replaceAll(Constants.SLASH, Constants.SHARP);
                                    } else {
                                        //change to element id
				    					tail = attValue.substring(keyIndex);
                                    }
				    			}else {
				    				//change to topic id
				    				tail = attValue.substring(keyIndex);
				    				//replace the topic id defined in the key's href 
				    				if(target.indexOf(Constants.SHARP) != -1){
				    					target = target.substring(0,target.indexOf(Constants.SHARP));
				    				}
				    			}
				    			copyAttribute("conref", target + tail);
				    			conkeyrefValid = true;
			    			}
			    		}else{
			    			final Properties prop = new Properties();
			    			prop.setProperty("%1", attValue);
			    			logger.logError(MessageUtils.getMessage("DOTJ046E", prop).toString());
			    		}
			    	}else{
			    		//conkeyref just has keyref
			    		if(keys.containsKey(attValue)){
			    			//get key's href
			    			final String value = keys.get(attValue);
			    			final String href = value.substring(0, value.lastIndexOf(Constants.LEFT_BRACKET));
			    			
			    			//Added by William on 2010-02-25 for bug:2957456 start
			    			final String updatedHref = updateHref(href);
			    			//Added by William on 2010-02-25 for bug:2957456 end
			
			    			//Added by William on 2009-06-25 for #12014 start
			    			final String id = null;
			    			
			    			final List<Boolean> list = DelayConrefUtils.getInstance().checkExport(href, id, attValue, tempDir);
			    			final boolean keyrefExported = list.get(1).booleanValue();
			    			//key is exported and transtype is eclipsehelp
			    			if(keyrefExported && transtype.equals(Constants.INDEX_TYPE_ECLIPSEHELP)){
			    			//remain the conkeyref attribute.
			    				copyAttribute("conkeyref", attValue);
			    			//Added by William on 2009-06-25 for #12014 end
			    			}else{
			    				//e.g conref = c.xml
			    				String target = updatedHref;
			    				//Added by William on 2010-05-17 for conkeyrefbug:3001705 start
			    				target = replaceExtName(target);
			    				//Added by William on 2010-05-17 for conkeyrefbug:3001705 end
			    				copyAttribute("conref", target);
				    			
				    			conkeyrefValid = true;
			    			}
			    		}else{
			    			final Properties prop = new Properties();
			    			prop.setProperty("%1", attValue);
			    			logger.logError(MessageUtils.getMessage("DOTJ046E", prop).toString());
			    		}	
			    	}
		    	}else{
		    		//invalid conkeyref value
		    		final Properties prop = new Properties();
	    			prop.setProperty("%1", attValue);
	    			logger.logError(MessageUtils.getMessage("DOTJ046E", prop).toString());
		    	}
		    }
		    
		    // replace '&' with '&amp;'
			//if (attValue.indexOf('&') > 0) {
				//attValue = StringUtils.replaceAll(attValue, "&", "&amp;");
			//}
		    attValue = StringUtils.escapeXML(attValue);
			
		    //output all attributes except colname and conkeyref, 
		    // if conkeyrefValid is true, then conref is not copied.
		    if (!Constants.ATTRIBUTE_NAME_COLNAME.equals(attQName)
		    		&& !Constants.ATTRIBUTE_NAME_NAMEST.equals(attQName)
		    		&& !Constants.ATTRIBUTE_NAME_DITAARCHVERSION.equals(attQName)
		    		&& !Constants.ATTRIBUTE_NAME_NAMEEND.equals(attQName)
		    		&& !Constants.ATTRIBUTE_NAME_CONKEYREF.equals(attQName) 
		    		&& !Constants.ATTRIBUTE_NAME_CONREF.equals(attQName) ){
		    		copyAttribute(attQName, attValue);
		    }
		    
		}
		String conref = atts.getValue("conref");
		if(conref != null && !conkeyrefValid){
			conref = replaceCONREF(atts);
			conref = StringUtils.escapeXML(conref);
			copyAttribute("conref", conref);
		}
	}

	/** Repalce extestion name for non-ditamap file
	 * @param target String
	 * @return String
	 */
	private String replaceExtName(String target) {
		final String fileName = FileUtils.resolveFile("", target);
		if(FileUtils.isDITATopicFile(fileName)){
			target = FileUtils.replaceExtName(target, extName);
		}
		return target;
	}

	/**
	 * Upate href.
	 * @param href String key's href
	 * @return
	 */
	private String updateHref(String href) {
		
		//Added by William on 2010-05-18 for bug:3001705 start
		final String filePath = new File(tempDir, this.inputFile).getAbsolutePath();
		
		final String keyValue = new File(tempDir, href).getAbsolutePath();
		
		final String updatedHref = FileUtils.getRelativePathFromMap(filePath, keyValue);
		//Added by William on 2010-05-18 for bug:3001705 end
		
		
		//String updatedHref = null;
		/*prefix = new File(prefix).getParent();
		if(StringUtils.isEmptyString(prefix)){
			updatedHref = href;
			updatedHref = updatedHref.replace(Constants.BACK_SLASH, Constants.SLASH);
		}else{
			updatedHref = prefix + Constants.SLASH +href;
			updatedHref = updatedHref.replace(Constants.BACK_SLASH, Constants.SLASH);
		}*/
		
		return updatedHref;
	}
	
	/**
	 * Add element specific attributes and default attributes.
	 * 
	 * @param qName
	 * @param atts
	 * @throws IOException
	 */
	private void copyElementName(String qName, AttributesImpl atts) throws IOException {
		if (Constants.ELEMENT_NAME_TGROUP.equals(qName)){
			
		    //Edited by William on 2009-11-27 for bug:1846993 start
		    //push into the stack.
		    if(colSpec!=null){
		    	colSpecStack.push(colSpec);
			    rowNumStack.push(rowNumber);
			    columnNumberStack.push(columnNumber);
			    columnNumberEndStack.push(columnNumberEnd);
			    rowsMapStack.push(rowsMap);
		    }
		    
		    columnNumber = 1; // initialize the column number
		    columnNumberEnd = 0;//totally columns
		    rowsMap = new HashMap<String, Integer>();
		    //new table initialize the col list
		    colSpec = new ArrayList<String>(Constants.INT_16);
		    //new table initialize the col list
		    rowNumber = 0;
		    //Edited by William on 2009-11-27 for bug:1846993 end
		}else if(Constants.ELEMENT_NAME_ROW.equals(qName)) {
		    columnNumber = 1; // initialize the column number
		    columnNumberEnd = 0;
		    //Added by William on 2009-06-30 for colname bug:2811358 start
		    //store the row number
		    rowNumber++;
		    //Added by William on 2009-06-30 for colname bug:2811358 end
		}else if(Constants.ELEMENT_NAME_COLSPEC.equals(qName)){
			columnNumber = columnNumberEnd +1;
			if(atts.getValue(Constants.ATTRIBUTE_NAME_COLNAME) != null){
				colSpec.add(atts.getValue(Constants.ATTRIBUTE_NAME_COLNAME));
			}else{
				colSpec.add(COLUMN_NAME_COL+columnNumber);
			}
			columnNumberEnd = columnNumber;
			//change the col name of colspec
			atts.addAttribute("", Constants.ATTRIBUTE_NAME_COLNAME, Constants.ATTRIBUTE_NAME_COLNAME, "CDATA", COLUMN_NAME_COL+columnNumber);
			//Added by William on 2009-06-30 for colname bug:2811358 start
			//total columns count
			totalColumns = columnNumberEnd;
			//Added by William on 2009-06-30 for colname bug:2811358 end
		}else if(Constants.ELEMENT_NAME_ENTRY.equals(qName)){
			
			/*columnNumber = getStartNumber(atts, columnNumberEnd);
			if(columnNumber > columnNumberEnd){
				atts.addAttribute("", Constants.ATTRIBUTE_NAME_COLNAME, Constants.ATTRIBUTE_NAME_COLNAME, "CDATA", COLUMN_NAME_COL+columnNumber);
				if (atts.getValue(Constants.ATTRIBUTE_NAME_NAMEST) != null){
					atts.addAttribute("", Constants.ATTRIBUTE_NAME_NAMEST, Constants.ATTRIBUTE_NAME_NAMEST, "CDATA", COLUMN_NAME_COL+columnNumber);
				}
				if (atts.getValue(Constants.ATTRIBUTE_NAME_NAMEEND) != null){
					atts.addAttribute("", Constants.ATTRIBUTE_NAME_NAMEEND, Constants.ATTRIBUTE_NAME_NAMEEND, "CDATA", COLUMN_NAME_COL+getEndNumber(atts, columnNumber));
				}
			}
			columnNumberEnd = getEndNumber(atts, columnNumber);*/
			
			
			
			//Added by William on 2009-06-30 for colname bug:2811358 start
			//Changed on 2010-11-19 for duplicate colname bug:3110418 start
			columnNumber = getStartNumber(atts, columnNumberEnd);

			
			if(columnNumber > columnNumberEnd){
				//The first row
				if(rowNumber == 1){
					atts.addAttribute("", Constants.ATTRIBUTE_NAME_COLNAME, Constants.ATTRIBUTE_NAME_COLNAME, "CDATA", COLUMN_NAME_COL+columnNumber);
					if (atts.getValue(Constants.ATTRIBUTE_NAME_NAMEST) != null){
						atts.addAttribute("", Constants.ATTRIBUTE_NAME_NAMEST, Constants.ATTRIBUTE_NAME_NAMEST, "CDATA", COLUMN_NAME_COL+columnNumber);
					}
					if (atts.getValue(Constants.ATTRIBUTE_NAME_NAMEEND) != null){
						atts.addAttribute("", Constants.ATTRIBUTE_NAME_NAMEEND, Constants.ATTRIBUTE_NAME_NAMEEND, "CDATA", COLUMN_NAME_COL+getEndNumber(atts, columnNumber));
					}
				//other row
				}else{
					//flag show whether former row spans rows
					boolean spanRows = false;
					int offset = 0;
					int currentCol = columnNumber;
					while(currentCol<=totalColumns) {
						int previous_offset=offset;
						//search from first row
						for(int row=1;row<rowNumber;row++){
							final String pos = String.valueOf(row) + String.valueOf(currentCol);
							if(rowsMap.containsKey(pos)){
								//get total span rows
								final int totalSpanRows = rowsMap.get(pos).intValue();
								if(rowNumber <= totalSpanRows){
									spanRows = true;
									offset ++;
								}
							}
						}
						
						if(offset>previous_offset) {
							currentCol = columnNumber + offset;
							previous_offset=offset;
						} else {
							break;
						}
	
					}
					columnNumber = columnNumber+offset;
					//if has morerows attribute
					if(atts.getValue(Constants.ATTRIBUTE_NAME_MOREROWS)!=null){
						final String pos = String.valueOf(rowNumber) + String.valueOf(columnNumber);
						//total span rows
						final int total = Integer.parseInt(atts.getValue(Constants.ATTRIBUTE_NAME_MOREROWS))+
						rowNumber;
						rowsMap.put(pos, Integer.valueOf(total));
						
					}
					
					atts.addAttribute("", Constants.ATTRIBUTE_NAME_COLNAME, Constants.ATTRIBUTE_NAME_COLNAME, "CDATA", COLUMN_NAME_COL+columnNumber);
					if (atts.getValue(Constants.ATTRIBUTE_NAME_NAMEST) != null){
						atts.addAttribute("", Constants.ATTRIBUTE_NAME_NAMEST, Constants.ATTRIBUTE_NAME_NAMEST, "CDATA", COLUMN_NAME_COL+columnNumber);
					}
					if (atts.getValue(Constants.ATTRIBUTE_NAME_NAMEEND) != null){
						atts.addAttribute("", Constants.ATTRIBUTE_NAME_NAMEEND, Constants.ATTRIBUTE_NAME_NAMEEND, "CDATA", COLUMN_NAME_COL+getEndNumber(atts, columnNumber));
					}
				}
			}
			columnNumberEnd = getEndNumber(atts, columnNumber);
			//Changed on 2010-11-19 for duplicate colname bug:3110418 end
			//Added by William on 2009-06-30 for colname bug:2811358 end
		}
	}

	@Override
    public void endCDATA() throws SAXException {
    	insideCDATA = false;
	    try{
	        output.write(Constants.CDATA_END);
	    }catch(final Exception e){
	    	logger.logException(e);
	    }
	}

    
	@Override
    public void endDocument() throws SAXException {
        try {
            output.flush();
        } catch (final Exception e) {
        	logger.logException(e);
        }
        
        //Added by William on 2010-06-01 for bug:3005748 start
        //@print
        ditaAttrUtils.reset();
		//Added by William on 2010-06-01 for bug:3005748 end
    }

    
	@Override
    public void endElement(String uri, String localName, String qName)
            throws SAXException {
		
		//Added by William on 2010-06-01 for bug:3005748 start
		//need to skip the tag
		if(ditaAttrUtils.needExcludeForPrintAttri(transtype)){
			//decrease level
			ditaAttrUtils.decreasePrintLevel();
			//don't write the end tag
			return;
		}
		//Added by William on 2010-06-01 for bug:3005748 end
		
    	if (foreignLevel > 0){
    		foreignLevel --;
    	}
        if (exclude) {
            if (level > 0) {
                // If it is the end of a child of an excluded tag, level
                // decrease
                level--;
            } else {
                exclude = false;
            }
        } else { // exclude shows whether it's excluded by filtering
            try {
                output.write(Constants.LESS_THAN + Constants.SLASH);
                output.write(qName);
                output.write(Constants.GREATER_THAN);
            } catch (final Exception e) {
            	logger.logException(e);
            }
        }
        //Added by William on 2009-11-27 for bug:1846993 embedded table bug start
        //note the tag shouldn't be excluded by filter file(bug:2925636 )
        if(Constants.ELEMENT_NAME_TGROUP.equals(qName) && !exclude){
        	//colSpecStack.pop();
        	//rowNumStack.pop();
        	//has tgroup tag
        	if(!colSpecStack.isEmpty()){
        		
        		colSpec = colSpecStack.peek();
        		rowNumber = rowNumStack.peek().intValue();
        		columnNumber = columnNumberStack.peek().intValue();
        		columnNumberEnd = columnNumberEndStack.peek().intValue();
        		rowsMap = rowsMapStack.peek();
        		
        		colSpecStack.pop();
            	rowNumStack.pop();
            	columnNumberStack.pop();
            	columnNumberEndStack.pop();
            	rowsMapStack.pop();
            	
        	}else{
        		//no more tgroup tag
        		colSpec = null;
        		rowNumber = 0;
        		columnNumber = 1;
        		columnNumberEnd = 0;
        		rowsMap = null;
        	}
        }
        //Added by William on 2009-11-27 for bug:1846993 embedded table bug end
        
    }

	@Override
    public void endEntity(String name) throws SAXException {
		if(!needResolveEntity){
			needResolveEntity = true;
		}
	}

	private int getEndNumber(Attributes atts, int columnStart) {
		int ret;
		if (atts.getValue("nameend") == null){
			return columnStart;
		}else{
			ret = colSpec.indexOf(atts.getValue("nameend")) + 1;
			if(ret == 0){
				return columnStart;
			}
			return ret;
		}
	}

	private int getStartNumber(Attributes atts, int previousEnd) {		
		int ret;
		if (atts.getValue("colnum") != null){
			return new Integer(atts.getValue("colnum")).intValue();
		}else if(atts.getValue("namest") != null){
			ret = colSpec.indexOf(atts.getValue("namest")) + 1;
			if(ret == 0){
				return previousEnd + 1;
			}
			return ret;
		}else if(atts.getValue("colname") != null){
			ret = colSpec.indexOf(atts.getValue("colname")) + 1;
			if(ret == 0){
				return previousEnd + 1;
			}
			return ret;
		}else{
			return previousEnd + 1;
		}
	}

	@Override
    public void ignorableWhitespace(char[] ch, int start, int length)
            throws SAXException {
        if (!exclude) { // exclude shows whether it's excluded by filtering
            try {
                output.write(ch, start, length);
            } catch (final Exception e) {
            	logger.logException(e);
            }
        }
    }
		
	@Override
    public void processingInstruction(String target, String data) throws SAXException {
    	if (!exclude) { // exclude shows whether it's excluded by filtering
            try {
            	super.processingInstruction(target, data);
            	output.write(Constants.LESS_THAN + Constants.QUESTION);
            	output.write(target);
            	if (data != null) {
            	    output.write(Constants.STRING_BLANK);
            	    output.write(data);
            	}
                output.write(Constants.QUESTION + Constants.GREATER_THAN);
            } catch (final Exception e) {
            	logger.logException(e);
            }
        }
	}

	@Override
    public InputSource resolveEntity(String publicId, String systemId)
            throws SAXException, IOException {
        if (catalogMap.get(publicId)!=null){
            final File dtdFile = new File((String)catalogMap.get(publicId));
            return new InputSource(dtdFile.getAbsolutePath());
        }else if (catalogMap.get(systemId) != null){
			final File schemaFile = new File((String) catalogMap.get(systemId));
			return new InputSource(schemaFile.getAbsolutePath());
		}
        return null;
    }
    
	@Override
    public void setContent(Content content) {        
        tempDir = (String) content.getValue();
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        if (!exclude) { // exclude shows whether it's excluded by filtering
            try {
                output.write(StringUtils.getEntity(name));
            } catch (final Exception e) {
            	logger.logException(e);
            }
        }
    }
	
    @Override
    public void startCDATA() throws SAXException {
	    try{
	    	insideCDATA = true;
	        output.write(Constants.CDATA_HEAD);
	    }catch(final Exception e){
	    	logger.logException(e);
	    }
	}

    
    @Override
    public void startDocument() throws SAXException {
        try {
            output.write(Constants.XML_HEAD);
            output.write(Constants.LINE_SEPARATOR);
            if(Constants.OS_NAME.toLowerCase().indexOf(OS_NAME_WINDOWS)==-1)
            {
                processingInstruction(PI_WORKDIR_TARGET, absolutePath);
            }else{
                processingInstruction(PI_WORKDIR_TARGET, Constants.SLASH + absolutePath);
            }
            output.write(Constants.LINE_SEPARATOR);
            if(path2Project != null){
                processingInstruction(PI_PATH2PROJ_TARGET, path2Project);
            }else{
                processingInstruction(PI_PATH2PROJ_TARGET, null);
            }
            output.write(Constants.LINE_SEPARATOR);
        } catch (final Exception e) {
        	logger.logException(e);
        }
    }

    
    @Override
    public void startElement(String uri, String localName, String qName,
            Attributes atts) throws SAXException {
        Integer value;
        Integer nextValue;
        String domains = null;
		final Properties params = new Properties();
		final String attrValue = atts.getValue(Constants.ATTRIBUTE_NAME_CLASS);
		
		//Added by William on 2010-06-01 for bug:3005748 start
		final String printValue = atts.getValue(Constants.ATTRIBUTE_NAME_PRINT);
		//increase element level for nested tags.
		ditaAttrUtils.increasePrintLevel(printValue);
		
		if(ditaAttrUtils.needExcludeForPrintAttri(transtype)){
			return;
		}
		//Added by William on 2010-06-01 for bug:3005748 end
		
        if (foreignLevel > 0){
        	foreignLevel ++;
        }else if( foreignLevel == 0){
        
			if(attrValue==null && !Constants.ELEMENT_NAME_DITA.equals(localName)){
	    		params.clear();
				params.put("%1", localName);
				logger.logInfo(MessageUtils.getMessage("DOTJ030I", params).toString());			
			}       
	        if (attrValue != null && attrValue.indexOf(Constants.ATTR_CLASS_VALUE_TOPIC) != -1){
	        	domains = atts.getValue(Constants.ATTRIBUTE_NAME_DOMAINS);
	        	if(domains==null){
	        		params.clear();
	    			params.put("%1", localName);
					logger.logInfo(MessageUtils.getMessage("DOTJ029I", params).toString());
	        	} else {
                    props = StringUtils.getExtProps(domains);
                }
	        }
	        if (attrValue != null && 
	        		(attrValue.indexOf(Constants.ATTR_CLASS_VALUE_FOREIGN) != -1 ||
	        				attrValue.indexOf(Constants.ATTR_CLASS_VALUE_UNKNOWN) != -1)){
	        	foreignLevel = 1;
	        }
        }
        
        this.validateAttributeValues(qName, atts);
        
        if (counterMap.containsKey(qName)) {
            value = counterMap.get(qName);
            nextValue = value + 1;
        } else {
            nextValue = 1;
        }
        counterMap.put(qName, nextValue);

        if (exclude) {
            // If it is the start of a child of an excluded tag, level increase
            level++;
        } else { // exclude shows whether it's excluded by filtering
            if (foreignLevel <= 1 && FilterUtils.needExclude(atts, props)){
                exclude = true;
                level = 0;
            }else{
                try {
                    output.write(Constants.LESS_THAN);
                    output.write(qName);
                    final AttributesImpl attsBuf = new AttributesImpl(atts);
                	copyElementName(qName, attsBuf);
                    // write the xtrf and xtrc attributes which contain debug
                    // information if it is dita elements (elements not in foreign/unknown)
                    if (foreignLevel <= 1){
                        attsBuf.addAttribute("", ATTRIBUTE_XTRF, ATTRIBUTE_XTRF, "CDATA", traceFilename);
                        attsBuf.addAttribute("", ATTRIBUTE_XTRC, ATTRIBUTE_XTRC, "CDATA", qName + Constants.COLON + nextValue.toString());
                    }
                    copyElementAttribute(qName, attsBuf);
                    output.write(Constants.GREATER_THAN);
                } catch (final Exception e) {
                	logger.logException(e);
                }// try
            } 
        }
    }

    @Override
    public void startEntity(String name) throws SAXException {
		if (!exclude) { // exclude shows whether it's excluded by filtering
            try {
            	needResolveEntity = StringUtils.checkEntity(name);
            	if(!needResolveEntity){
            		output.write(StringUtils.getEntity(name));
            	}
            } catch (final Exception e) {
            	logger.logException(e);
            }
        }

	}

   
    @Override
    public void write(String filename) {
		int index;
		int fileExtIndex;
		File outputFile;
		File dirFile;
		FileOutputStream fileOutput = null;
        exclude = false;
        needResolveEntity = true;
        
        inputFile = filename.substring(filename.lastIndexOf(Constants.STICK) + 1);
        
        if(null == keys){
        	keys = new HashMap<String, String>();
        	final Properties prop = new Properties();
    		if (! new File(tempDir).isAbsolute()){
    			tempDir = new File(tempDir).getAbsolutePath();
    		}
    		
    		final File ditafile = new File(tempDir, Constants.FILE_NAME_DITA_LIST);
    		final File ditaxmlfile = new File(tempDir, Constants.FILE_NAME_DITA_LIST_XML);
    		
    		InputStream in = null;
    		try{
    		if(ditaxmlfile.exists()){
    			in = new FileInputStream(ditaxmlfile);
    			prop.loadFromXML(in);
    		}else{
    			in = new FileInputStream(ditafile);
    			prop.load(in);
    		}
    		}catch (final Exception e) {
    			logger.logException(e);
    		} finally {
    			if (in != null) {
    				try {
    	                in.close();
                    } catch (final IOException e) {
                    	logger.logException(e);
                    }
    			}
    		}
    		
    		if(prop.getProperty(Constants.KEY_LIST).length()!=0){
	    		final String[] keylist = prop.getProperty(Constants.KEY_LIST).split(Constants.COMMA);
	    		String key;
	    		String value;
	    		for(final String keyinfo: keylist){
	    			//get the key name
	    			key = keyinfo.substring(0, keyinfo.indexOf(Constants.EQUAL));
	    			
	    			//Edited by William on 2010-02-25 for bug:2957456 start
	    			//value = keyinfo.substring(keyinfo.indexOf(Constants.EQUAL)+1, keyinfo.indexOf("("));
	    			//get the href value and source file name
	    			//e.g topics/target-topic-a.xml(maps/root-map-01.ditamap)
	    			value = keyinfo.substring(keyinfo.indexOf(Constants.EQUAL)+1);
	    			//Edited by William on 2010-02-25 for bug:2957456 end
	    			keys.put(key, value);
	    		}

    		}
        }
        index = filename.indexOf(Constants.STICK);
        fileExtIndex = filename.endsWith(Constants.FILE_EXTENSION_DITAMAP)
        			 ? -1
        			 : filename.lastIndexOf(Constants.DOT);
        
        try {
        	final StringBuffer outputFilename = new StringBuffer(tempDir + File.separator);
            if(index!=-1){
                traceFilename = filename.replace('|',File.separatorChar)
                .replace('/',File.separatorChar).replace('\\',File.separatorChar);
                outputFilename.append((fileExtIndex == -1 || fileExtIndex <= index)
                						?filename.substring(index+1)
                						:filename.substring(index+1, fileExtIndex)+extName);
                
                //when it is not the old solution 3
                if(OutputUtils.getGeneratecopyouter()!=OutputUtils.OLDSOLUTION){
                		if(isOutFile(traceFilename)){
                			
                			path2Project=this.getRelativePathFromOut(traceFilename);
                		}else{
                			path2Project=FileUtils.getRelativePathFromMap(traceFilename,OutputUtils.getInputMapPathName());
                			path2Project=new File(path2Project).getParent();
                			if(path2Project!=null && path2Project.length()>0){
                				path2Project=path2Project+File.separator;
                			}
                		}
                } else {
                    path2Project = FileUtils.getPathtoProject(filename.substring(index+1));
                }
            }else{
                traceFilename = filename;
                outputFilename.append((fileExtIndex == -1)
                					   ? filename
                					   : filename.substring(0, fileExtIndex)+extName);
                if(OutputUtils.getGeneratecopyouter()!=OutputUtils.OLDSOLUTION){
            		if(isOutFile(traceFilename)){
            			
            			path2Project=this.getRelativePathFromOut(traceFilename);
            		}else{
            			path2Project=FileUtils.getRelativePathFromMap(traceFilename,OutputUtils.getInputMapPathName());
            			path2Project=new File(path2Project).getParent();
            			if(path2Project!=null && path2Project.length()>0){
            				path2Project=path2Project+File.separator;
            			}
            		}
                } else {
                    path2Project = FileUtils.getPathtoProject(filename);
                }
            }
            outputFile = new File(outputFilename.toString());
            counterMap = new HashMap<String, Integer>();
            dirFile = outputFile.getParentFile();
            if (!dirFile.exists()) {
                dirFile.mkdirs();
            }
            absolutePath = dirFile.getCanonicalPath();
            fileOutput = new FileOutputStream(outputFile);
            output = new OutputStreamWriter(fileOutput, Constants.UTF8);

            
            // start to parse the file and direct to output in the temp
            // directory
            reader.setErrorHandler(new DITAOTXMLErrorHandler(traceFilename));
          //Added on 2010-08-24 for bug:3086552 start
            final File file = new File(traceFilename);
            final InputSource is = new InputSource(new FileInputStream(file));
    		//set system id bug:3086552
            if(setSystemid) {
                //is.setSystemId(URLUtil.correct(file).toString());
            	is.setSystemId(file.toURI().toURL().toString());
            }
            	
          //Added on 2010-08-24 for bug:3086552 end
            reader.parse(is);
            output.close();
        } catch (final Exception e) {
        	e.printStackTrace();
        	logger.logException(e);
        }finally {
            try {
                fileOutput.close();
            }catch (final Exception e) {
            	logger.logException(e);
            }
        }
    }
    
	/**
	 * Just for the overflowing files.
	 * @param overflowingFile overflowingFile
	 * @return relative path to out
	 */
    public String getRelativePathFromOut(String overflowingFile){
    	final File mapPathName=new File(OutputUtils.getInputMapPathName());
    	final File currFilePathName=new File(overflowingFile);
    	final String relativePath=FileUtils.getRelativePathFromMap( mapPathName.toString(),currFilePathName.toString());
    	final String outputDir=OutputUtils.getOutputDir();
    	final StringBuffer outputPathName=new StringBuffer(outputDir).append(File.separator).append("index.html");
    	final String finalOutFilePathName=FileUtils.resolveFile(outputDir,relativePath);
    	final String finalRelativePathName=FileUtils.getRelativePathFromMap(finalOutFilePathName,outputPathName.toString());
    	final String parentDir=new File(finalRelativePathName).getParent(); 
    	final StringBuffer finalRelativePath=new StringBuffer(parentDir);
    	if(finalRelativePath.length()>0){
    		finalRelativePath.append(File.separator);
    	}else{
    		finalRelativePath.append(".").append(File.separator);
    	}
    	return finalRelativePath.toString();	
    }
    
    private boolean isOutFile(String filePathName){
    	final String relativePath=FileUtils.getRelativePathFromMap(OutputUtils.getInputMapPathName(),new File(filePathName).getPath());
    	if(relativePath==null || relativePath.length()==0 || !relativePath.startsWith("..")){
    		return false;
    	}
    	return true;
    }
    
    private void validateAttributeValues(String qName, Attributes atts) {
    	
    	if (this.validateMap == null) {
            return;
        }

		final Properties prop = new Properties();

		for (int i = 0; i < atts.getLength(); i++) {
			final String attrName = atts.getQName(i);
			final String attrValue = atts.getValue(i);
			
			final HashMap<String, HashSet<String>> valueMap = this.validateMap.get(attrName);
			if (valueMap != null) {
				HashSet<String> valueSet = valueMap.get(qName);
				if (valueSet == null) {
                    valueSet = valueMap.get("*");
                }
				if (valueSet != null) {
					final String[] keylist = attrValue.trim().split("\\s+");
					for (final String s : keylist) {
						// Warning ? Value not valid.
						if (!StringUtils.isEmptyString(s) && !valueSet.contains(s)) {
							prop.clear();
							prop.put("%1", attrName);
							prop.put("%2", qName);
							prop.put("%3", attrValue);
							prop.put("%4", StringUtils.assembleString(valueSet,
									Constants.COMMA));
							logger.logWarn(MessageUtils.getMessage("DOTJ049W",
									prop).toString());
						}
					}
				}
			}
		}
	}

	/**
	 * @return the validateMap
	 */
	public HashMap<String, HashMap<String, HashSet<String>>> getValidateMap() {
		return validateMap;
	}

	/**
	 * @param validateMap the validateMap to set
	 */
	public void setValidateMap(HashMap<String, HashMap<String, HashSet<String>>> validateMap) {
		this.validateMap = validateMap;
	}
	/**
	 * Set default value map.
	 * @param defaultMap default value map
	 */
	public void setDefaultValueMap(HashMap<String, HashMap<String, String>> defaultMap) {
		this.defaultValueMap  = defaultMap;
	}
	
	//Added by William on 2009-07-18 for req #12014 start
	/**
	 * Get transtype.
	 * @return the transtype
	 */
	public String getTranstype() {
		return transtype;
	}

	/**
	 * Set transtype.
	 * @param transtype the transtype to set
	 */
	public void setTranstype(String transtype) {
		this.transtype = transtype;
	}
	//Added by William on 2009-07-18 for req #12014 end
	
	//Added by Alan Date:2009-08-04 --begin
	private static String extName;
	/**
	 * Get extension name.
	 * @return extension name
	 */
	public String getExtName() {
		return extName;
	}
	/**
	 * Set extension name.
	 * @param extName extension name
	 */
	public synchronized void setExtName(String extName) {
		DitaWriter.extName = extName;
	}
	//Added by Alan Date:2009-08-04 --end
	
}
