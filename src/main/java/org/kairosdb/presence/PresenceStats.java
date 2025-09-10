package org.kairosdb.presence;

import org.kairosdb.metrics4j.annotation.Key;
import org.kairosdb.metrics4j.collectors.LongCollector;

public interface PresenceStats
{
	LongCollector home(@Key("value")String value);
	LongCollector away(@Key("value")String value);
}
