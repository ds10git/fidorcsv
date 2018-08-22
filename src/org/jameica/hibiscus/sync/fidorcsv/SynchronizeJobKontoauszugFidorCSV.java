package org.jameica.hibiscus.sync.fidorcsv;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.rmi.RemoteException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Resource;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Widget;

import de.willuhn.datasource.GenericIterator;
import de.willuhn.jameica.gui.GUI;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.messaging.ImportMessage;
import de.willuhn.jameica.hbci.messaging.ObjectChangedMessage;
import de.willuhn.jameica.hbci.messaging.ObjectDeletedMessage;
import de.willuhn.jameica.hbci.messaging.SaldoMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJobKontoauszug;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.I18N;

/**
 * Implementierung des Kontoauszugsabruf fuer eine Beispiel-Bank.
 * Von der passenden Job-Klasse ableiten, damit der Job gefunden wird.
 */
public class SynchronizeJobKontoauszugFidorCSV extends SynchronizeJobKontoauszug implements SynchronizeJobFidorCSV
{
  static final String KEY_DOWNLOAD_PATH = "Download-Ordner";
  static final String KEY_AUTO_OPEN_LOGIN = "Loginseite automatisch öffnen";
  static final String KEY_LAST_TRANSACTION_UPDATE = "key.last.transaction.update";
  
  private final static I18N i18n = Application.getPluginLoader().getPlugin(Plugin.class).getResources().getI18N();
  private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd.MM.yyyy");

  @Resource
  private SynchronizeBackendFidorCSV backend = null;
  private static final boolean TESTING = false;
  
  /**
   * @see org.jameica.hibiscus.sync.fidorcsv.SynchronizeJobFidorCSV#execute()
   */
  @Override
  public void execute() throws Exception
  {
    Konto konto = (Konto) this.getContext(CTX_ENTITY); // wurde von ExampleSynchronizeJobProviderKontoauszug dort abgelegt
    
    // Hier koennen wir jetzt die Netzwerkverbindung zur Bank aufbauen, dort die
    // Kontoauszuege abrufen und in Hibiscus anlegen
    
    Logger.info("Rufe Umsätze ab für " + backend.getName());
    
    ////////////////
    /*String username = konto.getMeta(ExampleSynchronizeBackend.PROP_USERNAME,null);
    String password = konto.getMeta(ExampleSynchronizeBackend.PROP_PASSWORD,null);
    if (username == null || username.length() == 0)
      throw new ApplicationException(i18n.tr("Bitte geben Sie Ihren Benutzernamen in den Synchronisationsoptionen ein"));
    
    if (password == null || password.length() == 0)
      throw new ApplicationException(i18n.tr("Bitte geben Sie Ihr Passwort in den Synchronisationsoptionen ein"));

    Logger.info("username: " + username);
    ////////////////
*/    
    
    final AtomicReference<Result> handle = new AtomicReference<SynchronizeJobKontoauszugFidorCSV.Result>(null);
    
    //openLink(URL_BASE + URL_PATH_LOGIN);
    GUI.getDisplay().syncExec(new Runnable() {
      public void run() {
        try {
          handle.set(showLinkDialog(konto));
        } catch (RemoteException | ParseException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
    });
    
    if(handle.get() != null && handle.get().mProgress) {
      final File directory = new File(handle.get().mDownloadPath);
      
      if(directory.isDirectory()) {
        File[] csvFiles = directory.listFiles((FileFilter)file -> {
          return file.getName().endsWith("-Fidorpay-Transaktionen.csv") && file.lastModified() > mLastClickOnLink;
        });
        
        if(csvFiles.length == 0 && handle.get().mSaldoCorrect != null) {
          try {
            Double saldo = parseSaldo(handle.get().mSaldoCorrect);
            
            if(saldo != null) {
              konto.setSaldo(saldo);
              konto.store();
              
              // Und per Messaging Bescheid geben, dass das Konto einen neuen Saldo hat     
              Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
            }
          }catch(NumberFormatException nfe) {}
        }
        
        for(File csvFile : csvFiles) {
          Logger.info("Loading transactions from file: " + csvFile.getAbsolutePath());
          boolean delete = translate(csvFile, konto, handle.get().mSaldoCorrect);
          
          if(delete && !TESTING) {
            if(!csvFile.delete()) {
              csvFile.deleteOnExit();
            }
          }
        }
      }
    }
  }
  
  private Double parseSaldo(final String saldo) {
    Double endSaldo = null;
    
    if(saldo != null) {
      try {
        endSaldo = Double.parseDouble(saldo.replace(",", "."));
      }catch(NumberFormatException nfe) {
        final Display display = GUI.getDisplay();
        display.syncExec(new Runnable() {
          public void run() {
            Shell shell = new Shell(display, SWT.PRIMARY_MODAL | SWT.SHEET);
            
            final MessageBox msg = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
            msg.setText("Fehler");
            msg.setMessage("Der eingegebene Saldo '"+saldo+"' ist fehlerhaft.\nImport wird abgebrochen.");
            
            msg.open();
          }
        });
      }
    }
        
    return endSaldo;
  }
  
  private boolean translate(final File source, final Konto konto, final String saldoCorrect) throws RemoteException, ParseException, ApplicationException {
	  boolean result = true;
    Double endSaldo = null;
    
    if(saldoCorrect != null) {
      endSaldo = parseSaldo(saldoCorrect);
      
      if(endSaldo == null) {
        return false;
      }
    }
    
    ArrayList<Umsatz> stack = new ArrayList<>();
    
    {      
      final AtomicBoolean firstFidorCSV = new AtomicBoolean(konto.getSaldoDatum() != null && konto.getMeta(KEY_LAST_TRANSACTION_UPDATE, null) == null); 

      if(konto.getSaldoDatum() == null || firstFidorCSV.get()) {
        final AtomicReference<String> handle = new AtomicReference<String>(null);
        
        GUI.getDisplay().syncExec(new Runnable() {
          public void run() {
            handle.set(showSaldoDialog(saldoCorrect, firstFidorCSV.get()));
          }
        });
        
        if((endSaldo = parseSaldo(handle.get())) == null) {
          return false;
        }
      }
    }
    
    Date oldest = getStartDate(konto);
    
    BufferedReader in = null;
    
    final ArrayList<Date> listDatesAvailable = new ArrayList<>();
    
    String line = null;
    int count = 0;
    
    try {
      in = new BufferedReader(new InputStreamReader(new FileInputStream(source), "UTF-8"));
      
      in.readLine();
      
      while((line = in.readLine()) != null) {
        count++;
        String[] parts = line.split(";");
        
        if(parts.length == 4) {
          Date date = DATE_FORMAT.parse(parts[0]);
            
          if(oldest == null || date.before(oldest)) {
            oldest = date;
          }
          
          String[] senderParts = parts[2].split(",\\s+");
          String name = null;
          
          String bic = null;
          
          Umsatz newUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
          newUmsatz.setKonto(konto);
          newUmsatz.setDatum(date);
          newUmsatz.setValuta(date);
          
          if(!listDatesAvailable.contains(date)) {
            listDatesAvailable.add(date);
          }
          
          for(String part : senderParts) {
            int index = part.indexOf(":")+1;
            
            if(part.startsWith("BIC:")) {
              bic = part;
              newUmsatz.setGegenkontoBLZ(part.substring(index+1));
            }
            
            if(part.startsWith("IBAN:")) {
              newUmsatz.setGegenkontoNummer(part.substring(index+1));
            }
            
            if(index == 0) {
              if(name != null) {
                name += ", " + part;
              }
            }
            else {
              String partName = part.substring(0,index-1).trim();
              String value = part.substring(index).trim();
              
              if(partName.equals("Absender") || partName.equals("Empfänger")) {
                name = value;
              }
            }
          }
          
          if(name != null) {
            newUmsatz.setGegenkontoName(name);
          }
          
          int index = name != null ? parts[1].indexOf(name) : -1;
          
          if(bic != null) {
            int test = parts[1].indexOf(bic);
            
            if(test != -1) {
              index = test + bic.length();
              name = "";
            }
          }
          
          String zweck = "";
          
          if(index != -1) {
            if(parts[1].length() > index+name.length()+1) {
              zweck = parts[1].substring(index+name.length()+1).trim();
            }
          }
          else {
            if(parts[1].startsWith("Überweisung:")) {
              zweck = parts[1].substring(parts[1].indexOf(":")+1).trim();
              newUmsatz.setArt("Überweisung");
            }
            else {
              zweck = parts[1].trim();
            }
          }
          
          if(zweck.length() > 35) {
            newUmsatz.setZweck(zweck.substring(0, 35));
            
            if(zweck.length() > 70) {
              newUmsatz.setZweck2(zweck.substring(35, 70));
              
              ArrayList<String> zwecke = new ArrayList<>();
              
              for(int i = 70; i < zweck.length(); i += 35) {
                zwecke.add(zweck.substring(i,Math.min(zweck.length(), i+35)));
              }
              
              newUmsatz.setWeitereVerwendungszwecke(zwecke.toArray(new String[zwecke.size()]));
            }
            else {
              newUmsatz.setZweck2(zweck.substring(35));
            }
          }
          else {
            newUmsatz.setZweck(zweck);
          }
          
          newUmsatz.setBetrag(Double.parseDouble(parts[3].replace(".", "").replace(",", ".")));
          stack.add(0, newUmsatz);
        }
      }
    }catch(Exception e) {
      // if something went wrong we don't want to process
      // the CSV file, so clear all transactions
      stack.clear();
      stack = null;
      result = false;
      Logger.error("Fehlerhafter Umsatz in Zeile " + count + ": " + line);
      Logger.error("Fehler beim Einlesen der CSV-Datei '" + source.getAbsolutePath() + "' ", e);
      e.printStackTrace();
    }finally {
      if(in != null) {
        try {
          in.close();
        } catch (IOException e) {}
      }
    }
    
    if(!listDatesAvailable.isEmpty()) {
      Collections.sort(listDatesAvailable);
    }
    
    final HashMap<Date,Date> mapShiftDates = new HashMap<>();
    
    {
      final Calendar cet = Calendar.getInstance(TimeZone.getTimeZone("CET"));
      
      // fill shift date map
      for(int i = 0; i < listDatesAvailable.size()-1; i++) {
        for(int j = i+1; j < listDatesAvailable.size(); j++) {
          cet.setTime(listDatesAvailable.get(j));
          
          // dates on Saturday or Sunday are no dates that
          // are shifted to so we need to skip those dates
          if(cet.get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY && cet.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
            mapShiftDates.put(listDatesAvailable.get(i), listDatesAvailable.get(j));
            break;
          }
        }
      }
    }
    
    // an empty or malformed CSV file must not be processed 
    if(stack != null && !stack.isEmpty()) {
      Double saldo = konto.getSaldo();
      
      // Wir holen uns die Umsaetze seit dem letzen Abruf von der Datenbank
      GenericIterator<?> existing = konto.getUmsaetze(oldest,null);
      existing.begin();
      
      Date last = null;
      
      final ArrayList<Umsatz> existingList = new ArrayList<>();
      
      while(existing.hasNext()) {
        Umsatz next = (Umsatz)existing.next();
        
        if(last == null || last.compareTo(next.getDatum()) < 0) {
          last = next.getDatum();
        }
        
        existingList.add(next);
        
        if(!mapShiftDates.containsKey(next.getDatum())) {
          for(int i = listDatesAvailable.size()-1; i >= 0; i--) {
            if(listDatesAvailable.get(i).after(next.getDatum())) {
              mapShiftDates.put(next.getDatum(), listDatesAvailable.get(i));
            }
            else {
              break;
            }
          }
        }
      }
      
      boolean hadTransactions = !stack.isEmpty();
      
      // -to find transactions with changed date, we compare
      //  the content of the CSV file with the content of
      //  the database, if an entry is in the database but
      //  not in the CSV file it's date was changed or it
      //  was somehow deleted, that's what we need to find out
      // -for this to work, we always have to download
      //  a CSV file that contains back to 5 days before
      //  last update, since there might be such big shifts
      //  for holidays 
      for (int i = existingList.size()-1; i >= 0; i--) {
        final Umsatz test = existingList.get(i);
        
        Umsatz toRemove = null;
        
        for (Umsatz umsatz : stack) {
          // only transactions with a date smaller than or equal to the last known
          // in the database have to be checked if they already exist
          if(umsatz.getDatum().compareTo(last) <= 0 && isEquivalent(test, umsatz)) {
            // we don't need the transaction in the CSV file because it's already 
            // in the database, so we remove it from handling it later on
            // it needs not to be handled because it's date wasn't shifted 
            toRemove = umsatz;
            break;
          } // to find shifted transactions we have to check for the shift date instead of the last known in the database
          else if(!mapShiftDates.isEmpty() && mapShiftDates.get(test.getDatum()) != null && umsatz.getDatum().equals(mapShiftDates.get(test.getDatum())) && isEquivalent(test, umsatz, true)) {
            test.setDatum(umsatz.getDatum());
            test.setValuta(umsatz.getDatum());
            test.store();
            
            Application.getMessagingFactory().sendMessage(new ObjectChangedMessage(test));
            
            // we don't need the transaction in the CSV file because it's already 
            // in the database, so we remove it from handling it later on
            toRemove = umsatz;
            break;
          }
        }
        
        if(toRemove != null) {
          // finally remove the equivalent CSV entry from transaction list
          stack.remove(toRemove);
        } 
        else {
          // if the test transaction has no equivalent in the CSV file, it somehow
          // got deleted so we need to delete it from the database also
          saldo -= test.getBetrag();
          test.delete();
          
          Application.getMessagingFactory().sendMessage(new ObjectDeletedMessage(test));
        }
      }
      
      // calculate start saldo from current end saldo
      // therefor we need to go through all new transactions
      // and substract it's betrag from the end saldo
      if(endSaldo != null) {
        for(Umsatz umsatz : stack) {
          endSaldo -= umsatz.getBetrag();
        }
        
        saldo = endSaldo;
        endSaldo = null;
      }
      
      for(Umsatz umsatz : stack) {
       /* boolean toContinue = false;
        
        // only if there are no shift dates we have to check for existing
        // transactions in the database because we didn't already
        // in the search for date shifted transactions
        if(mapShiftDates.isEmpty()) {
          for (int i = existingList.size()-1; i >= 0; i--) {
            final Umsatz test = existingList.get(i);
            
            if(isEquivalent(test, umsatz)) {
              // we have found and equivalent transaction in the CSV file
              // so we need to remove it from the existing list and must not
              // add the transaction from the CSV file because it would be
              // a duplicate of the transaction in the database.
              existingList.remove(i);
              toContinue = true;
              break;
            }
          }
          
          if(toContinue) {
            continue;
          }
        }*/
        
        saldo += umsatz.getBetrag();
        
        umsatz.setSaldo(saldo);
        
        // Neuer Umsatz. Anlegen
        umsatz.store();
        
        // Per Messaging Bescheid geben, dass es einen neuen Umsatz gibt. Der wird dann sofort in der Liste angezeigt
        Application.getMessagingFactory().sendMessage(new ImportMessage(umsatz));
      }
      
      if(hadTransactions || endSaldo != null) {
        if(endSaldo != null) {
          saldo = endSaldo;
        }
        // Zum Schluss sollte noch der neue Saldo des Kontos sowie das Datum des Abrufes
        // im Konto gespeichert werden
        konto.setSaldo(saldo); // gegen sinnvollen Wert ersetzen ;)
        konto.store();
        
        konto.setMeta(KEY_LAST_TRANSACTION_UPDATE, DATE_FORMAT.format(konto.getSaldoDatum()));
        // Und per Messaging Bescheid geben, dass das Konto einen neuen Saldo hat     
        Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
      }
    }
    else if(stack != null && endSaldo != null) {
      // if the user has entered an end saldo but no transaction were found
      // we need to update the saldo of the konto, without saving it as 
      konto.setSaldo(endSaldo); 
      konto.store();
      
      konto.setMeta(KEY_LAST_TRANSACTION_UPDATE, DATE_FORMAT.format(konto.getSaldoDatum()));
      // Und per Messaging Bescheid geben, dass das Konto einen neuen Saldo hat     
      Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
    }
    
    return result;
  }
  
  private long mLastClickOnLink = 0;
  
  private static final String URL_BASE = "https://banking.fidor.de";
  private static final String URL_PATH_LOGIN = "/login";
  private static final String URL_PATH_CSV_GENERATION = "/smart-account/transactions.csv?from={0}&time_selection=from_to&to={1}";
  
  private Date getStartDate(final Konto konto) throws RemoteException, ParseException {
    final String lastUpdate = konto.getMeta(KEY_LAST_TRANSACTION_UPDATE, null);
    
    Date fromDate = lastUpdate != null && konto.getSaldoDatum() != null ? DATE_FORMAT.parse(lastUpdate) : konto.getSaldoDatum();
    
    if(fromDate == null) {
      final Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("CET"));
      
      cal.add(Calendar.MONTH, -6);
      
      fromDate = cal.getTime();
    }
    else {
      Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("CET"));
      cal.setTime(fromDate);
      cal.add(Calendar.DAY_OF_YEAR, -5);
      
      fromDate = cal.getTime();
    }
    
    return fromDate;
  }
  
  private String showSaldoDialog(final String initialSaldo, final boolean firstFidorCSV) {
    final AtomicReference<String> result = new AtomicReference<String>(null);
    
    Display display = GUI.getDisplay();
    Shell shell = new Shell(display, SWT.PRIMARY_MODAL | SWT.SHEET);
    shell.setText("Fidor CSV-Download");
    
    GridLayout gridLayout = new GridLayout();
    gridLayout.numColumns = 2;
    gridLayout.horizontalSpacing = 7;
    gridLayout.verticalSpacing = 7;
    gridLayout.marginWidth = 10;
    gridLayout.marginHeight = 10;
    
    shell.setLayout(gridLayout);

    Label info = new Label(shell, SWT.WRAP);
    info.setText("Sie rufen die Umsätze zum ersten Mal " + (firstFidorCSV ? "mit Fidor-CSV " : "") + "ab, bitte\ngeben Sie den aktuellen Saldo des Kontos an,\nda dieser nicht ausgelesen werden kann.");
    
    GridData gridData = new GridData();
    gridData.horizontalSpan = 2;
    info.setLayoutData(gridData);
    
    final Label labelSaldo = new Label(shell, SWT.HORIZONTAL);
    labelSaldo.setText("Kontostand am "+DATE_FORMAT.format(new Date())+":");
    
    gridData = new GridData();
    gridData.verticalIndent = 5;
    labelSaldo.setLayoutData(gridData);
    
    final Text saldoCurrent = new Text(shell, SWT.SINGLE);
    saldoCurrent.setText(initialSaldo != null ? initialSaldo : "");
    
    gridData = new GridData(GridData.FILL_HORIZONTAL);
    gridData.verticalIndent = 5;
    saldoCurrent.setLayoutData(gridData);
    
    Button okay = new Button(shell, SWT.PUSH);
    okay.setEnabled(false);
    okay.setText("Saldo übernehmen");
    okay.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        result.set(saldoCurrent.getText());
        
        shell.dispose();
      }
    });
    
    gridData = new GridData(GridData.HORIZONTAL_ALIGN_END);
    gridData.horizontalSpan = 2;
    
    okay.setLayoutData(gridData);
    
    saldoCurrent.addModifyListener(e -> {
      okay.setEnabled(!((Text)e.widget).getText().trim().isEmpty());
    });
    
    shell.setDefaultButton(okay);
    shell.pack();
    shell.open();
    
    waitForShell(shell, display);
    
    return result.get();
  }
  
  private Result showLinkDialog(final Konto konto) throws RemoteException, ParseException {
    final Result result = new Result(false);
    
    String from = DATE_FORMAT.format(getStartDate(konto));
    String to = DATE_FORMAT.format(new Date());
    
    Display display = GUI.getDisplay();
    Shell shell = new Shell(display, SWT.PRIMARY_MODAL | SWT.SHEET);
    shell.setText("Fidor CSV-Download");
    
    GridLayout gridLayout = new GridLayout();
    gridLayout.numColumns = 4;
    gridLayout.horizontalSpacing = 7;
    gridLayout.verticalSpacing = 7;
    gridLayout.marginWidth = 10;
    gridLayout.marginHeight = 10;
    
    shell.setLayout(gridLayout);

    Label help = new Label(shell, SWT.WRAP);
    help.setText("Klicken Sie auf den ersten Link, um das Login in das Konto mit dem Webbrowser zu öffnen. Nachdem Sie\neingeloggt sind, können Sie durch einen Klick auf den zweiten Link das Erstellen der CSV-Datei starten.\nWenn die CSV-Datei erstellt wurde, laden Sie diese in das angegebene Download-Verzeichnis herunter\nund klicken Sie auf 'CSV-Datei einlesen', um die Umsätze zu übernehmen.");
    
    GridData gridData = new GridData();
    gridData.horizontalSpan = 4;
    help.setLayoutData(gridData);
    
    Label separator = new Label(shell, SWT.HORIZONTAL | SWT.SEPARATOR);
    gridData = new GridData(GridData.FILL_HORIZONTAL);
    gridData.horizontalSpan = 4;
    gridData.verticalIndent = 5;
    separator.setLayoutData(gridData);
    
    int indent = 5;
    
    final Text saldoCurrent;
    final Button saldoCorrect;
    
    if(konto.getSaldoDatum() != null && konto.getMeta(KEY_LAST_TRANSACTION_UPDATE, null) != null) {
      saldoCorrect = new Button(shell, SWT.CHECK);
      saldoCorrect.setText("Saldo korrigieren");
      gridData = new GridData(GridData.FILL_HORIZONTAL);
      gridData.verticalIndent = indent;
      gridData.horizontalSpan = 4;
      
      saldoCorrect.setLayoutData(gridData);
      
      final Label labelSaldo = new Label(shell, SWT.HORIZONTAL);
      labelSaldo.setText("Kontostand am "+DATE_FORMAT.format(new Date())+":");
      
      gridData = new GridData();
      labelSaldo.setLayoutData(gridData);
      labelSaldo.setEnabled(false);
      
      saldoCurrent = new Text(shell, SWT.SINGLE);
      saldoCurrent.setText(String.format("%.2f", konto.getSaldo()));
      saldoCurrent.setEnabled(false);
      
      gridData = new GridData(GridData.FILL_HORIZONTAL);
      gridData.horizontalSpan = 3;
      saldoCurrent.setLayoutData(gridData);
      
      indent = 0;
      
      saldoCorrect.addSelectionListener(new SelectionAdapter() {
        @Override
        public void widgetSelected(SelectionEvent e) {
          labelSaldo.setEnabled((((Button)e.widget).getSelection()));
          saldoCurrent.setEnabled(labelSaldo.isEnabled());
        }
      });
    }
    else {
      saldoCurrent = null;
      saldoCorrect = null;
    }
    
    Label label = new Label(shell, SWT.HORIZONTAL);
    label.setText("Download-Ordner:");
    
    gridData = new GridData();
    gridData.verticalIndent = indent;
    label.setLayoutData(gridData);
    
    final Text downloadP = new Text(shell, SWT.SINGLE);
    downloadP.setText(getProperty(konto, KEY_DOWNLOAD_PATH, System.getProperty("user.home")+File.separator+"Downloads"));
    
    gridData = new GridData(GridData.FILL_HORIZONTAL);
    gridData.verticalIndent = indent;
    downloadP.setLayoutData(gridData);
    
    Button select = new Button(shell, SWT.PUSH);
    select.setText("Ordner auswählen");
    
    gridData = new GridData(GridData.HORIZONTAL_ALIGN_END);
    select.setLayoutData(gridData);
    
    select.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        final String filterPath = new File(downloadP.getText()).isDirectory() ? downloadP.getText() : System.getProperty("user.home");  
        
        DirectoryDialog dialog = new DirectoryDialog(shell, SWT.OPEN);
        dialog.setFilterPath(filterPath);
        
        String result = dialog.open();
        
        if(result != null && new File(result).isDirectory()) {
          downloadP.setText(result);
        }
      }
    });
    
    SelectionAdapter openLinks = new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        Program.launch((String)((Widget)e.getSource()).getData());
      }
    };
    
    gridData = new GridData();

    gridData.horizontalAlignment = GridData.FILL;

    gridData.horizontalSpan = 4;
    
    Link login = new Link(shell, SWT.NULL);
    login.setData(URL_BASE+URL_PATH_LOGIN);
    login.setText("<a href=\"\">"+URL_BASE+URL_PATH_LOGIN+"</a>");
    login.addSelectionListener(openLinks);
    login.setLayoutData(gridData);
    
    Link linkS = new Link(shell, SWT.NULL);
    linkS.setData(getCsvLink(from,to));
    linkS.setText("<a href=\"\">"+getCsvLink(from,to).replace("&", "&&")+"</a>");
    linkS.addSelectionListener(openLinks);
    linkS.setLayoutData(gridData);
    
    Button cancel = new Button(shell, SWT.PUSH);
    cancel.setText("Abbrechen");
    cancel.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        shell.dispose();
      }
    });
    
    Button okay = new Button(shell, SWT.PUSH);
    okay.setText("CSV-Datei einlesen");
    okay.addSelectionListener(new SelectionAdapter() {
      @Override
      public void widgetSelected(SelectionEvent e) {
        try {
          konto.setMeta(KEY_DOWNLOAD_PATH,downloadP.getText());
        } catch (RemoteException e1) {
          // TODO Auto-generated catch block
          e1.printStackTrace();
        }
        
        result.setProceed(downloadP.getText(), saldoCorrect != null && saldoCorrect.getSelection() ? saldoCurrent.getText() : null);
        shell.dispose();
      }
    });
    
    gridData = new GridData(GridData.HORIZONTAL_ALIGN_END);
    gridData.horizontalSpan = 3;
    
    okay.setLayoutData(gridData);
    
    shell.setDefaultButton(okay);
    shell.pack();
    shell.open();
    
    if(getProperty(konto, KEY_AUTO_OPEN_LOGIN, false)) {
      Program.launch(URL_BASE+URL_PATH_LOGIN);
    }
    
    waitForShell(shell, display);
    
    return result;
  }

  private void waitForShell(final Shell shell, final Display display) {
    while (!shell.isDisposed()) {
      if (!display.readAndDispatch()) {
          display.sleep();
      }
    }    
  }
  
  private static String getPath(String path, String... replace) {
    if(replace != null) {
      for(int i = 0; i < replace.length; i++) {
        path = path.replace("{"+i+"}", replace[i]);
      }
    }
    
    return URL_BASE + path;
  }
  
  private static String getCsvLink(final String from, final String to) {
    return getPath(URL_PATH_CSV_GENERATION,from,to);
  }
  
  private static final boolean stringEquals(final String s1, final String s2) {
    return (s1 == null && s2 == null ) || 
        (s1 != null && s2 != null && s1.equals(s2)) 
        || (s1 == null && s2 != null && s2.trim().isEmpty())
        || (s2 == null && s1 != null && s1.trim().isEmpty());
  }
  
  private static final String fillZweck(final Umsatz u) throws RemoteException {
    final StringBuilder zweck = new StringBuilder();
    
    if(u.getZweck() != null) {
      zweck.append(u.getZweck());
    }
    
    if(u.getZweck2() != null) {
      zweck.append(u.getZweck2());
    }
    
    if(u.getWeitereVerwendungszwecke() != null) {
      for(String z : u.getWeitereVerwendungszwecke()) {
        zweck.append(z);
      }
    }
    
    return zweck.toString();
  }
  
  private static final boolean isSameZwecke(final Umsatz u1, final Umsatz u2) throws RemoteException {
    return fillZweck(u1).equals(fillZweck(u2));
  }
  
  private static String getProperty(final Konto konto, final String key, final String defaultValue) throws RemoteException {
    String result = konto.getMeta(key, "").trim();
    
    if(result.length() == 0 && defaultValue != null) {
      result = defaultValue;
    }
    
    return result;
  }
  
  public static boolean getProperty(final Konto konto, final String key, final boolean defaultValue) throws RemoteException {
    String openLogin = getProperty(konto, key, defaultValue ? "true" : "false").toLowerCase();
    
    return openLogin.equals("ja") || openLogin.equals("yes") || openLogin.equals("true") || openLogin.equals("1");
  }
  
  private static final class Result {
    private boolean mProgress;
    private String mDownloadPath;
    private String mSaldoCorrect;
    
    private Result(final boolean progress) {
      mProgress = progress;
      mDownloadPath = null;
    }
    
    private void setProceed(final String downloadPath, final String saldoCorrect) {
      mProgress = true;
      mDownloadPath = downloadPath;
      mSaldoCorrect = saldoCorrect;
    }
  }
  
  private static final boolean isEquivalent(final Umsatz u1, final Umsatz u2) throws RemoteException {
    return isEquivalent(u1, u2, false);
  }
  
  private static final boolean isEquivalent(final Umsatz u1, final Umsatz u2, final boolean ignoreDate) throws RemoteException {
    return isSameZwecke(u1, u2) &&
    stringEquals(u1.getGegenkontoNummer(), u2.getGegenkontoNummer()) &&
    stringEquals(u1.getGegenkontoBLZ(), u2.getGegenkontoBLZ()) &&
    stringEquals(u1.getGegenkontoName(), u2.getGegenkontoName()) &&
    stringEquals(u1.getArt(), u2.getArt()) &&
    u1.getBetrag() == u2.getBetrag() &&
    (ignoreDate || u1.getDatum().equals(u2.getDatum()));
  }
}



