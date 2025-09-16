package org.kairosdb.presence;

import com.google.common.base.Stopwatch;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.kairosdb.core.scheduler.KairosDBJob;
import org.kairosdb.eventbus.Subscribe;
import org.kairosdb.events.DataPointEvent;
import org.kairosdb.metrics4j.MetricSourceManager;
import org.quartz.*;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.quartz.TriggerBuilder.newTrigger;

/**
 * Kairos privdes a scheduler through quartz.  All we have to do is implement KairosDBJob
 */
public class PresenceCheck implements KairosDBJob
{
	public static final PresenceStats stats = MetricSourceManager.getSource(PresenceStats.class);

	public static final String METRIC_NAME_CONFIG = "kairosdb.presence.metric";
	public static final String TAG_NAME_CONFIG = "kairosdb.presence.tag";
	public static final String VALUES_CONFIG = "kairosdb.presence.values";
	public static final String HOST_CONFIG = "kairosdb.presence.mqtt.host";
	public static final String CLIENT_ID_CONFIG = "kairosdb.presence.mqtt.client_id";
	public static final String TOPIC_CONFIG = "kairosdb.presence.mqtt.topic";

	private final String m_metricName;
	private final String m_tagName;
	private final Set<String> m_values;
	private final String m_topic;
	private final Object m_mapLock = new Object();
	private final MqttClient m_mqttClient;

	private final Map<String, Stopwatch> m_presenceMap;

	@Inject
	public PresenceCheck(
			@Named(METRIC_NAME_CONFIG) String metricName,
			@Named(TAG_NAME_CONFIG) String tagName,
			@Named(VALUES_CONFIG) List<String> values,
			@Named(HOST_CONFIG) String mqttHost,
			@Named(CLIENT_ID_CONFIG) String mqttClientId,
			@Named(TOPIC_CONFIG) String topic) throws MqttException
	{
		m_metricName = metricName;
		m_tagName = tagName;
		m_values = new HashSet<>(values);
		m_topic = topic;

		m_presenceMap = new HashMap<>();

		m_mqttClient = new MqttClient(mqttHost, mqttClientId, new MemoryPersistence());
		m_mqttClient.setTimeToWait(0);
		MqttConnectOptions connOpts = new MqttConnectOptions();
		connOpts.setCleanSession(true);
		m_mqttClient.connect(connOpts);
	}

	/**
	 * Subscriber endpoint that gets every data point sent to kairos.  The priority of this subscriber is set in presence.conf
	 * @param dataPoint
	 */
	@Subscribe
	public void checkPresence(DataPointEvent dataPoint)
	{
		String metricName = dataPoint.getMetricName();
		if (m_metricName.equals(metricName))
		{
			String presenceValue = dataPoint.getTags().get(m_tagName);
			if (presenceValue != null && m_values.contains(presenceValue))
			{
				synchronized (m_mapLock) {
					m_presenceMap.compute(presenceValue, (s, stopwatch) -> {
						if (stopwatch == null) {
							stats.home(presenceValue).put(1);
							sendUpdate(presenceValue, "HOME");
							return Stopwatch.createStarted();
						}
						else {
							stopwatch.reset();
							stopwatch.start();
							return stopwatch;
						}
					});
				}
			}
		}
	}

	@Override
	public Trigger getTrigger()
	{
		return (newTrigger()
				.withIdentity(this.getClass().getSimpleName())
				.withSchedule(SimpleScheduleBuilder.repeatMinutelyForever(1))
				.build());
	}

	@Override
	public void interrupt()
	{

	}

	@Override
	public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException
	{
		List<String> awayList = new ArrayList<>();
		synchronized (m_mapLock)
		{
			Iterator<Map.Entry<String, Stopwatch>> iterator = m_presenceMap.entrySet().iterator();
			while (iterator.hasNext())
			{
				Map.Entry<String, Stopwatch> presenceEntry = iterator.next();
				if (presenceEntry.getValue().elapsed(TimeUnit.MINUTES) >= 10) {
					awayList.add(presenceEntry.getKey());
					iterator.remove();
				}
			}
		}

		for (String value : awayList) {
			stats.away(value).put(1);
			sendUpdate(value, "AWAY");
		}
	}

	private void sendUpdate(String value, String status)
	{
		String content      = "{\"value\":\""+value+"\",\"status\":\""+status+"\"}";
		int qos             = 1;

		try {
			MqttMessage message = new MqttMessage(content.getBytes());
			message.setQos(qos);
			m_mqttClient.publish(m_topic, message);
		} catch(MqttException me) {
			System.out.println("reason "+me.getReasonCode());
			System.out.println("msg "+me.getMessage());
			System.out.println("loc "+me.getLocalizedMessage());
			System.out.println("cause "+me.getCause());
			System.out.println("excep "+me);
			me.printStackTrace();
		}
	}
}
