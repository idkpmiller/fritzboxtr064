package org.openhab.binding.fritzboxtr064.internal;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.JobKey.jobKey;
import static org.quartz.TriggerBuilder.newTrigger;
import static org.quartz.TriggerKey.triggerKey;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openhab.binding.fritzboxtr064.FritzboxTr064BindingProvider;
import org.openhab.binding.fritzboxtr064.internal.FritzboxTr064GenericBindingProvider.FritzboxTr064BindingConfig;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.Item;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.library.types.OnOffType;
import org.openhab.library.tel.types.CallType;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/***
 * Wrapper class which handles all data/comm. when call monitoing is used
 * Thread control class
 * @author gitbock
 *
 */
public class CallMonitor extends Thread{
	
	//port number to connect at fbox
	private final int _DEFAULT_MONITOR_PORT = 1012;
	
	//Thread control flag
	protected boolean _interrupted = false;
	
	//time to wait before reconnecting
	private long _reconnectTime = 60000L;
	
	// Event Publisher from parent Generic Binding
	// to be able to pass item updates within this class
	protected EventPublisher _eventPublisher;
	
	// Default openhab Logger
	protected final static Logger logger = LoggerFactory.getLogger(FritzboxTr064Binding.class);
	
	// Main Monitor Thread receiving fbox messages
	private static CallMonitorThread _monitorThread;
	
	//ip and port to connect
	protected String _ip;
	protected int _port;
	
	
	//Providers to be able to extract all required items
	private Collection<FritzboxTr064BindingProvider> _providers;

	
	/***
	 * 
	 * @param url from openhab.cfg to connect to fbox
	 * @param ep eventPublisher to pass updates to items
	 * @param providers all items relevant for this binding
	 */
	public CallMonitor(String url, EventPublisher ep, Collection<FritzboxTr064BindingProvider> providers ){
		this._eventPublisher = ep;
		this._ip = parseIpFromUrl(url);
		this._port = _DEFAULT_MONITOR_PORT;
		this._providers = providers;
	}
	
	
	/***
	 * In Main Config only the TR064 URL is provided. Need IP for Socket connection.
	 * Parses the IP from URL String
	 * @param url String
	 * @return IP address from url
	 */
	private String parseIpFromUrl(String url) {
		String ip = "";
		Pattern pat = Pattern.compile("(https?://)([^:^/]*)(:\\d*)?(.*)?");
		
		Matcher m = pat.matcher(url);
		if(m.find()){
			ip = m.group(2);
		}
		else{
			logger.error("Cannot get IP from FritzBox URL: "+url);
		}
		return ip;
	}


	/**
	 * A quartz scheduler job to simply do a reconnection to the FritzBox.
	 */
	public class ReconnectJob implements Job {
		
		public void execute(JobExecutionContext arg0) throws JobExecutionException {
			Logger logger = LoggerFactory.getLogger(FritzboxTr064Binding.class);
			logger.info("Reconnecting Job executed");
			if (_monitorThread != null) {
				// let's end the old thread
				_monitorThread.interrupt();
				_monitorThread = null;
			}
			// create a new thread for listening to the FritzBox
			_monitorThread = new CallMonitorThread();
			_monitorThread.start();
			
		}

	}
	
	/***
	 * reset the connection to fbox periodically
	 */
	public void setupReconnectJob(){
		try {
			String cronPattern = "0 0 0 * * ?";
			Scheduler sched = StdSchedulerFactory.getDefaultScheduler();
                    
            JobKey jobKey = jobKey("Reconnect", "FritzBox");
            TriggerKey triggerKey = triggerKey("Reconnect", "FritzBox");
            
            if (sched.checkExists(jobKey)) {
                logger.debug("Daily reconnection job already exists");
            } else {
                CronScheduleBuilder scheduleBuilder = 
                		CronScheduleBuilder.cronSchedule(cronPattern);
                
                JobDetail job = newJob(ReconnectJob.class)
                        .withIdentity(jobKey)
                        .build();

                CronTrigger trigger = newTrigger()
                        .withIdentity(triggerKey)
                        .withSchedule(scheduleBuilder)
                        .build();

                sched.scheduleJob(job, trigger);
                logger.debug("Scheduled a daily reconnection to FritzBox: "+cronPattern);
            }
		} catch (SchedulerException e) {
			logger.warn("Could not create daily reconnection job", e);
		}
	}
	
	/***
	 * cancel the reconnect job
	 */
	public void shutdownReconnectJob() {
		Scheduler sched = null;
		try {
			sched = StdSchedulerFactory.getDefaultScheduler();
			JobKey jobKey = jobKey("Reconnect", "FritzBox");
		    TriggerKey triggerKey = triggerKey("Reconnect", "FritzBox");
		    if (sched.checkExists(jobKey)) {
		        logger.debug("Found reconnection job. Shutting down...");
		        sched.deleteJob(jobKey);
		    }
		
		} catch (SchedulerException e) {
			logger.warn("Error shutting down reconnect job: "+e.getLocalizedMessage());
		}
        
	
		
	}


	
	
	
	/***
	 * thread for setting up socket to fbox, listening for messages, parsing them
	 * and updating items
	 * @author gitbock
	 *
	 */
	private class CallMonitorThread extends Thread{

		
		
		//Providers to be able to extract all required items
		//private Collection<FritzboxTr064BindingProvider> _providers;
		
		// Event Publisher from parent Generic Binding
		// to be able to pass item updates within this class
		//private EventPublisher _eventPublisher;
		
		//Socket to connect
		private Socket _socket; 
		
		public CallMonitorThread() {
			
		}
		
		@Override
		public void run() {
			while (!_interrupted) {
				
				if (_ip != null) {
					BufferedReader reader = null;
					try {
						logger.info("Attempting connection to FritzBox on {}:{}...", _ip, _port);
						_socket = new Socket(_ip, _port);
						reader = new BufferedReader(new InputStreamReader(_socket.getInputStream()));
						// reset the retry interval
						_reconnectTime = 60000L;
					} catch (Exception e) {
						logger.warn("Error attempting to connect to FritzBox. Retrying in " + _reconnectTime / 1000L + "s.", e);
						try {
							Thread.sleep(_reconnectTime);
						} catch (InterruptedException ex) {
							_interrupted = true;
						}
						// wait another more minute the next time
						_reconnectTime += 60000L;
					}
					if (reader != null) {
						logger.info("Connected to FritzBox on {}:{}", _ip, _port);
						while (!_interrupted) {
							try {
								String line = reader.readLine();
								if (line != null) {
									logger.debug("Received raw call string from fbox: "+line);
									CallEvent ce = new CallEvent(line);
									if(ce.parseRawEvent()){
										handleEventType(ce);
									}
									else{
										logger.error("Call Event could not be parsed!");
							
									}
									try {
										// wait a moment, so that rules can be
										// processed
										// see
										// http://knx-user-forum.de/openhab/25024-bug-im-fritzbox-binding.html
										sleep(100L);
									} catch (InterruptedException e) {
									}
								}
							} catch (IOException e) {
								if (_interrupted) {
									logger.info("Lost connection to Fritzbox because of interrupt");
								} else {
									logger.error("Lost connection to FritzBox", e);
								}
								break;
							}
						}
					}
				}
			}
		}
		
		/**
		 * Handle call event and update item as required
		 * 
		 * @param ce call event to process
		 */
		private void handleEventType(CallEvent ce) {
			//cycle through all items
			logger.debug("Searching item to receive call event: "+ce.toString());
			for (FritzboxTr064BindingProvider provider : _providers) { 
				for(String itemName : provider.getItemNames() ){ //check each item relevant for this binding		
					FritzboxTr064BindingConfig conf = provider.getBindingConfigByItemName(itemName); //config object for item
					Class<? extends Item> itemType = conf.getItemType(); //which type is this item?
					org.openhab.core.types.State state = null;

					if (ce.get_callType().equals("DISCONNECT")) {
						state = itemType.isAssignableFrom(SwitchItem.class) ? OnOffType.OFF : CallType.EMPTY;
					}
					if (ce.get_callType().equals("RING")) {
						state = itemType.isAssignableFrom(SwitchItem.class) ? OnOffType.ON : new CallType(ce.get_externalNo(), ce.get_internalNo());
					}
					if (ce.get_callType().equals("CONNECT")){
						state = itemType.isAssignableFrom(SwitchItem.class) ? OnOffType.ON : new CallType(ce.get_externalNo(), ce.get_internalNo());
					}
					if (ce.get_callType().equals("CALL")){
						state = itemType.isAssignableFrom(SwitchItem.class) ? OnOffType.ON : new CallType(ce.get_internalNo(), ce.get_externalNo());
					}
					
					if (state != null) {
						logger.debug("Dispatching call type "+ ce.get_callType() +" to item " + itemName + " as "+state.toString());
						_eventPublisher.postUpdate(itemName, state);
					}
				}
			}
		}
			
			
			
			
			
		
		
		/**
		 * Notifies the thread to terminate itself. The current connection will
		 * be closed.
		 */
		public void interrupt() {
			_interrupted = true;
			if (_socket != null) {
				try {
					_socket.close();
					logger.debug("Socket to FritzBox closed");
				} catch (IOException e) {
					logger.warn("Existing connection to FritzBox cannot be closed", e);
				}
			}
		}

	}
	

	public void stopThread() {
		logger.debug("Stopping monitor Thread...");
		if (_monitorThread != null) {
			_monitorThread.interrupt();
			_monitorThread = null;
		}
		
	}


	public void startThread() {
		logger.debug("Starting monitor Thread...");
		if (_monitorThread != null) {
			logger.warn("Old monitor Thread was still running");
			// let's end the old thread
			_monitorThread.interrupt();
			_monitorThread = null;
		}
		// create a new thread for listening to the FritzBox
		_monitorThread = new CallMonitorThread();
		_monitorThread.start();
		
	}
	
}
