/**
 * Copyright 2010 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jbpm.bpmn2.xml;

import java.util.List;
import java.util.Map;

import org.drools.core.xml.ExtensibleXmlParser;
import org.jbpm.bpmn2.core.*;
import org.jbpm.bpmn2.core.Error;
import org.jbpm.compiler.xml.ProcessBuildData;
import org.jbpm.process.core.event.EventTypeFilter;
import org.jbpm.process.core.timer.DateTimeUtils;
import org.jbpm.process.core.timer.Timer;
import org.jbpm.workflow.core.Node;
import org.jbpm.workflow.core.impl.DroolsConsequenceAction;
import org.jbpm.workflow.core.node.*;
import org.w3c.dom.Element;
import org.xml.sax.*;

public class StartEventHandler extends AbstractNodeHandler {
    
    protected Node createNode(Attributes attrs) {
        return new StartNode();
    }
    
    @SuppressWarnings("unchecked")
	public Class generateNodeFor() {
        return StartNode.class;
    }

    @SuppressWarnings("unchecked")
    protected void handleNode(final Node node, final Element element, final String uri, 
            final String localName, final ExtensibleXmlParser parser) throws SAXException {
        super.handleNode(node, element, uri, localName, parser);
        StartNode startNode = (StartNode) node;
        startNode.setInterrupting(Boolean.parseBoolean(element.getAttribute("isInterrupting")));
        
        org.w3c.dom.Node xmlNode = element.getFirstChild();
        while (xmlNode != null) {
            String nodeName = xmlNode.getNodeName();
            if ("dataOutput".equals(nodeName)) {
                readDataOutput(xmlNode, startNode);
            } else if ("dataOutputAssociation".equals(nodeName)) {
                readDataOutputAssociation(xmlNode, startNode);
            } else if ("outputSet".equals(nodeName)) { 
                // p. 225, BPMN2 spec (2011-01-03)
                // InputSet and OutputSet elements imply that process execution should wait for them to be filled
                // and are therefore not applicable to catch events
                String message = "Ignoring <" + nodeName + "> element: "
                        + "<" + nodeName + "> elements should not be used on start or other catch events.";
                SAXParseException saxpe = new SAXParseException( message, parser.getLocator() );
                parser.warning(saxpe);
                // no exception thrown for backwards compatibility (we used to ignore these elements)
            } else if ("conditionalEventDefinition".equals(nodeName)) {
                String constraint = null;
                org.w3c.dom.Node subNode = xmlNode.getFirstChild();
                while (subNode != null) {
                    String subnodeName = subNode.getNodeName();
                    if ("condition".equals(subnodeName)) {
                        constraint = xmlNode.getTextContent();
                        break;
                    }
                    subNode = subNode.getNextSibling();
                }
                ConstraintTrigger trigger = new ConstraintTrigger();
                trigger.setConstraint(constraint);
                startNode.addTrigger(trigger);
                break;
            } else if ("signalEventDefinition".equals(nodeName)) {
                String type = ((Element) xmlNode).getAttribute("signalRef");
                if (type != null && type.trim().length() > 0) {
                    addTriggerWithInMappings(startNode, type);
                }
            } else if ("messageEventDefinition".equals(nodeName)) {
                String messageRef = ((Element) xmlNode).getAttribute("messageRef");
                Map<String, Message> messages = (Map<String, Message>)
                    ((ProcessBuildData) parser.getData()).getMetaData("Messages");
                if (messages == null) {
                    throw new IllegalArgumentException("No messages found");
                }
                Message message = messages.get(messageRef);
                if (message == null) {
                    throw new IllegalArgumentException("Could not find message " + messageRef);
                }
                startNode.setMetaData("MessageType", message.getType());
                
                addTriggerWithInMappings(startNode, "Message-" + messageRef);
            } else if ("timerEventDefinition".equals(nodeName)) {
            	org.w3c.dom.Node subNode = xmlNode.getFirstChild();
                while (subNode instanceof Element) {
                    String subNodeName = subNode.getNodeName();
                    Timer timer = null;   
                    
                    if (parser.getParent() instanceof EventSubProcessNode) {
                        // handle timer on start events like normal (non rule) timers for event sub process
                        timer = new Timer();
                        
                        addTriggerWithInMappings(startNode, "Timer-" + ((EventSubProcessNode) parser.getParent()).getId());
                    }
                    if ("timeCycle".equals(subNodeName)) {
                        String period = "";
                        String repeatLimit = "-1";
                        String delay = subNode.getTextContent();
                        
                        String language = ((Element) subNode).getAttribute("language");
                        if (language == null || language.trim().length() == 0) {
                        	language = "int";
                        }
                        if ("int".equalsIgnoreCase(language)) {
                            long[] repeatTimer = DateTimeUtils.parseRepeatableDateTime(delay);
                            if (repeatTimer.length == 1) {
                                startNode.setMetaData("TimerDef", delay);
                                delay = Long.toString(repeatTimer[0]);
                                period = Long.toString(repeatTimer[0]);
                            } else {
                                startNode.setMetaData("TimerDef", delay);
                                repeatLimit = Long.toString(repeatTimer[0]+1);
                                delay = Long.toString(repeatTimer[1]);
                                period = Long.toString(repeatTimer[2]);
                                
                            }
                        }
                        if (delay != null && delay.trim().length() > 0) {
                            startNode.setMetaData("TimerType", Timer.TIME_CYCLE);
                            if (timer != null) {
                                timer.setDelay(delay);
                                timer.setPeriod(period);
                                timer.setTimeType(Integer.valueOf(Timer.TIME_CYCLE));
                                ((EventSubProcessNode) parser.getParent()).addTimer(timer, new DroolsConsequenceAction("java", ""));
                            } else {
    	                        ConstraintTrigger trigger = new ConstraintTrigger();
    	                        trigger.setConstraint("");
    	                        if ("int".equals(language)) {
    	                            if (repeatLimit.equals("-1")) {
    	                                trigger.setHeader("timer (int:" + delay + " " + period + ")");
    	                            } else {
    	                                trigger.setHeader("timer (int:" + delay + " " + period + " repeat-limit=" + repeatLimit + ")");
    	                            }
    	                        } else {
    	                        	trigger.setHeader("timer (" + language + ":" + delay + ")");
    	                        }
    	                        startNode.addTrigger(trigger);
                            }
	                        break;
                        }
                    } else if ("timeDuration".equals(subNodeName)) {
                        String period = Long.toString(DateTimeUtils.parseDuration(subNode.getTextContent()));
                        
                        if (period != null && period.trim().length() > 0) {
                            startNode.setMetaData("TimerDef", period);
                            startNode.setMetaData("TimerType", Timer.TIME_DURATION);
                            if (timer != null) {
                                timer.setDelay(period);
                                timer.setTimeType(Integer.valueOf(Timer.TIME_DURATION));
                                ((EventSubProcessNode) parser.getParent()).addTimer(timer, new DroolsConsequenceAction("java", ""));
                            } else {
                                ConstraintTrigger trigger = new ConstraintTrigger();
                                trigger.setConstraint("");
                                trigger.setHeader("timer (int:" + period + " 0)");
                                
                                startNode.addTrigger(trigger);
                            }
                            break;
                        }
                    } else if ("timeDate".equals(subNodeName)) {
                        String period = Long.toString(DateTimeUtils.parseDateAsDuration(subNode.getTextContent()));
                 
                        if (period != null && period.trim().length() > 0) {
                            startNode.setMetaData("TimerDef", period);
                            startNode.setMetaData("TimerType", Timer.TIME_DATE);
                            if (timer != null) {
                                timer.setDate(period);
                                timer.setTimeType(Integer.valueOf(Timer.TIME_DATE));
                                ((EventSubProcessNode) parser.getParent()).addTimer(timer, new DroolsConsequenceAction("java", ""));
                            } else {
                                ConstraintTrigger trigger = new ConstraintTrigger();
                                trigger.setConstraint("");
                                trigger.setHeader("timer (int:" + period + " 0)");
                                
                                startNode.addTrigger(trigger);
                            }
                            break;
                        }
                    }
                    subNode = subNode.getNextSibling();
                    
               }
                // following event definitions are only for event sub process and will be validated to not be included in top process definitions
            } else if ("errorEventDefinition".equals(nodeName)) {
                if( ! startNode.isInterrupting() ) { 
                    String errorMsg = "Ignoring (possibly default) 'isInterupting' attribute on <startEvent> element: "
                            + "Error Start Events in an Event Sub-Process always interrupt the containing process.";
                    SAXParseException saxpe = new SAXParseException( errorMsg, parser.getLocator() );
                    parser.warning(saxpe);
                    // NO exception thrown because we don't know if the <startEvent> isInterrupting attr is 'true' because of
                    // 1. it's the default value for the 'isInterupting' attribute (user did NOT add a 'isInterupting' in BPMN2 def)
                    //   or
                    // 2. the user set the 'isInterupting' attribute to 'true' in the BPMN2 def
                    // BPMN2 spec (p.225-226, (2011-01-03)) implies that 
                    //   - a <startEvent> in an Event Sub-Process 
                    //    - *without* the 'isInterupting' attribute always interupts (containing process)
                    startNode.setInterrupting(true);
                }
                String errorRef = ((Element) xmlNode).getAttribute("errorRef");
                if (errorRef != null && errorRef.trim().length() > 0) {
                    Map<String, Error> errors = (Map<String, Error>)
                        ((ProcessBuildData) parser.getData()).getMetaData("Errors");
                    if (errors == null) {
                        throw new IllegalArgumentException("No errors found");
                    }
                    Error error = errors.get(errorRef);
                    
                    if (error == null) {
                        throw new IllegalArgumentException("Could not find error " + errorRef);
                    }
                    addTriggerWithInMappings(startNode, "Error-" + error.getErrorCode());
                }
            } else if ("escalationEventDefinition".equals(nodeName)) {
                String escalationRef = ((Element) xmlNode).getAttribute("escalationRef");
                if (escalationRef != null && escalationRef.trim().length() > 0) {
                    Map<String, Escalation> escalations = (Map<String, Escalation>)
                        ((ProcessBuildData) parser.getData()).getMetaData("Escalations");
                    if (escalations == null) {
                        throw new IllegalArgumentException("No escalations found");
                    }
                    Escalation escalation = escalations.get(escalationRef);
                    if (escalation == null) {
                        throw new IllegalArgumentException("Could not find escalation " + escalationRef);
                    }                    
                
                    addTriggerWithInMappings(startNode, "Escalation-" + escalation.getEscalationCode());
                }
            } else if ("compensateEventDefinition".equals(nodeName)) {
                String activityRef = ((Element) xmlNode).getAttribute("activityRef");
                if (activityRef != null && activityRef.trim().length() > 0) {    
                    addTriggerWithInMappings(startNode, "Compensate-" + activityRef);
                }
            }
            xmlNode = xmlNode.getNextSibling();
        }
    }
    
    private void addTriggerWithInMappings(StartNode startNode, String triggerEventType) { 
        EventTrigger trigger = new EventTrigger();
        EventTypeFilter eventFilter = new EventTypeFilter();
        eventFilter.setType(triggerEventType);
        trigger.addEventFilter(eventFilter);
        
        String mapping = (String) startNode.getMetaData("TriggerMapping");
        if (mapping != null) {
            trigger.addInMapping(mapping, startNode.getOutMapping(mapping));
        }
        
        startNode.addTrigger(trigger);
    }
    
    protected void readDataOutputAssociation(org.w3c.dom.Node xmlNode, StartNode startNode) {
        // sourceRef 
        org.w3c.dom.Node subNode = xmlNode.getFirstChild();
        if( ! "sourceRef".equals(subNode.getNodeName()) ) {
            throw new IllegalArgumentException("No sourceRef found in dataOutputAssociation in startEvent");
        }
        String source = subNode.getTextContent();
        if( dataOutputs.get(source) == null ) { 
           throw new IllegalArgumentException( "No dataOutput could be found for the dataOutputAssociation." );
        }
        
        // targetRef
        subNode = subNode.getNextSibling();
        if( ! "targetRef".equals(subNode.getNodeName()) ) { 
            throw new IllegalArgumentException("No targetRef found in dataOutputAssociation in startEvent");
        }
        String target = subNode.getTextContent();
        startNode.setMetaData("TriggerMapping", target);
        
        subNode = subNode.getNextSibling();
        if( subNode != null ) { 
            // no support for assignments or transformations
            throw new UnsupportedOperationException(subNode.getNodeName() + " elements in dataOutputAssociations are not yet supported.");
        }
        startNode.addOutMapping(target, dataOutputs.get(source));
    }
    
    // The results of this method are only used to check syntax
    protected void readDataOutput(org.w3c.dom.Node xmlNode, StartNode startNode) {
        String id = ((Element) xmlNode).getAttribute("id");
        String outputName = ((Element) xmlNode).getAttribute("name");
        dataOutputs.put(id, outputName);
    }
    
    public void writeNode(Node node, StringBuilder xmlDump, int metaDataType) {
		StartNode startNode = (StartNode) node;
		writeNode("startEvent", startNode, xmlDump, metaDataType);
		xmlDump.append(" isInterrupting=\"" );
		if (startNode.isInterrupting()) {
		    xmlDump.append("true");
		} else { 
		    xmlDump.append("false");
		}
		xmlDump.append("\"" );
		
		List<Trigger> triggers = startNode.getTriggers();
		if (triggers != null) {
		    xmlDump.append(">" + EOL);
		    if (triggers.size() > 1) {
		        throw new IllegalArgumentException("Multiple start triggers not supported");
		    }
		    Trigger trigger = triggers.get(0);
		    if (trigger instanceof ConstraintTrigger) {
		    	ConstraintTrigger constraintTrigger = (ConstraintTrigger) trigger;
		    	if (constraintTrigger.getHeader() == null) {
			        xmlDump.append("      <conditionalEventDefinition>" + EOL);
	                xmlDump.append("        <condition xsi:type=\"tFormalExpression\" language=\"" + XmlBPMNProcessDumper.RULE_LANGUAGE + "\">" + constraintTrigger.getConstraint() + "</condition>" + EOL);
	                xmlDump.append("      </conditionalEventDefinition>" + EOL);
		    	} else {
		    	    Integer timerType = (Integer) startNode.getMetaData("TimerType");
		    	    if (timerType == Timer.TIME_DURATION) {
		    	        xmlDump.append("      <timerEventDefinition>" + EOL);
	                    xmlDump.append("        <timeDuration xsi:type=\"tFormalExpression\" >" + startNode.getMetaData("TimerDef") + "</timeDuration>" + EOL);
	                    xmlDump.append("      </timerEventDefinition>" + EOL);
		    	    } else if (timerType == Timer.TIME_DATE) {
		    	        xmlDump.append("      <timerEventDefinition>" + EOL);
	                    xmlDump.append("        <timeDate xsi:type=\"tFormalExpression\" >" + startNode.getMetaData("TimerDef") + "</timeDate>" + EOL);
	                    xmlDump.append("      </timerEventDefinition>" + EOL);    
                    } else {
    		    		String header = constraintTrigger.getHeader();
    		    		header = header.substring(7, header.length() - 1);
    		    		int index = header.indexOf(":");
    		    		String language = header.substring(0, index);
    		    		header = header.substring(index + 1);
    		    		String cycle = null;
    		    		if (startNode.getMetaData("TimerDef") != null){
    		    		    cycle = (String) startNode.getMetaData("TimerDef");
    		    		}else if ("int".equals(language)) {
    		    			int lenght = (header.length() - 1)/2;
    			    		cycle = header.substring(0, lenght);
    		    		} else {
    		    			cycle = header;
    		    		}
    			        xmlDump.append("      <timerEventDefinition>" + EOL);
    	                xmlDump.append("        <timeCycle xsi:type=\"tFormalExpression\" language=\"" + language + "\">" + cycle + "</timeCycle>" + EOL);
    	                xmlDump.append("      </timerEventDefinition>" + EOL);
                    }
		    	}
		    } else if (trigger instanceof EventTrigger) {
		        EventTrigger eventTrigger = (EventTrigger) trigger;
		        String mapping = null;
		        if (!trigger.getInMappings().isEmpty()) {
		            mapping = eventTrigger.getInMappings().keySet().iterator().next();
		        }
		        else { 
		            mapping = (String) startNode.getMetaData("TriggerMapping");
		        }
		        
		        if( mapping != null ) { 
		            xmlDump.append(
	                    "      <dataOutput id=\"_" + startNode.getId() + "_Output\" />" + EOL +
                        "      <dataOutputAssociation>" + EOL +
                        "        <sourceRef>_" + startNode.getId() + "_Output</sourceRef>" + EOL +
                        "        <targetRef>" + mapping + "</targetRef>" + EOL +
                        "      </dataOutputAssociation>" + EOL);
		        }
		        
		        String type = ((EventTypeFilter) eventTrigger.getEventFilters().get(0)).getType();
		        if (type.startsWith("Message-")) {
                    type = type.substring(8);
                    xmlDump.append("      <messageEventDefinition messageRef=\"" + type + "\"/>" + EOL);
                } else if (type.startsWith("Error-")) {
                    type = type.substring(6);
                    xmlDump.append("      <errorEventDefinition errorRef=\"" + type + "\"/>" + EOL);
                } else if (type.startsWith("Escalation-")) {
                    type = type.substring(11);
                    xmlDump.append("      <escalationEventDefinition escalationRef=\"" + type + "\"/>" + EOL);
                } else if (type.startsWith("Compensate-")) {
                    type = type.substring(11);
                    xmlDump.append("      <compensateEventDefinition activityRef=\"" + type + "\"/>" + EOL);
                } else {
                    xmlDump.append("      <signalEventDefinition signalRef=\"" + type + "\" />" + EOL);
                }
            } else {
		        throw new IllegalArgumentException("Unsupported trigger type " + trigger);
		    }
		    endNode("startEvent", xmlDump);
		} else {
		    endNode(xmlDump);
		}
	}

}
