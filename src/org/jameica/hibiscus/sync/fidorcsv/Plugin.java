package org.jameica.hibiscus.sync.fidorcsv;

import de.willuhn.jameica.plugin.AbstractPlugin;
import de.willuhn.jameica.plugin.Version;
import de.willuhn.util.ApplicationException;

/**
 * Basis-Klasse des Plugins.
 */
public class Plugin extends AbstractPlugin
{
  public static Version VERSION_OLD = null;
  
  @Override
  public void update(Version oldVersion) throws ApplicationException {
    VERSION_OLD = oldVersion;
  }
}


