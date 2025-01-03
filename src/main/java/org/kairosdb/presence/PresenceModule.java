package org.kairosdb.presence;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Singleton;

public class PresenceModule implements Module
{
	@Override
	public void configure(Binder binder)
	{
		//we just need to bind our class and the subscriber endpoint and scheduler will pick it up.
		binder.bind(PresenceCheck.class).in(Singleton.class);
	}
}
