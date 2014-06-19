package gdsc.foci.converter;

import gdsc.foci.FindFoci;

import org.jdesktop.beansbinding.Converter;

public class BackgroundThresholdMethodEnabledConverter extends Converter<Integer,Boolean>
{
	@Override
	public Boolean convertForward(Integer paramS)
	{
		int backgroundMethod = paramS.intValue();
		return Boolean.valueOf(
				backgroundMethod == FindFoci.BACKGROUND_AUTO_THRESHOLD);
	}

	@Override
	public Integer convertReverse(Boolean paramT)
	{
		// N/A
		return null;
	}
}
