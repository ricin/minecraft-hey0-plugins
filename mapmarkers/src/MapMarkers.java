import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class MapMarkers extends Plugin {

	private static final String LOG_PREFIX = "[MapMarkers] : ";

	public PropertiesFile properties;
	public String markersFile;
	public SimpleDateFormat dateFormat;
	public int writeInterval = 5;
	public int staleTimeout;
	public boolean showSpawn;
	public Date date;
	public Date oldDate;
	public Calendar cal;
	public String[] lineArray;

	static ArrayList<String> markerList = new ArrayList<String>();
	static JSONArray markersArray = new JSONArray();

	private MapMarkersListener listener = new MapMarkersListener();

	protected static final Logger log = Logger.getLogger("Minecraft");
	private final Semaphore available = new Semaphore(1, true);

	public MapMarkers() {
	}

	public void initialize() {
		etc.getLoader().addListener(PluginLoader.Hook.COMMAND, listener, this, PluginListener.Priority.MEDIUM);
		etc.getLoader().addListener(PluginLoader.Hook.PLAYER_MOVE, listener, this, PluginListener.Priority.MEDIUM);
		etc.getLoader().addListener(PluginLoader.Hook.LOGIN, listener, this, PluginListener.Priority.MEDIUM);
		etc.getLoader().addListener(PluginLoader.Hook.DISCONNECT, listener, this, PluginListener.Priority.LOW);
	}

	public boolean load() {
		if (properties == null) {
			properties = new PropertiesFile("mapmarkers.properties");
		} else {
			properties.load();
		}

		writeInterval = properties.getInt("write.interval", 5);

		try {
			dateFormat = new SimpleDateFormat(properties.getString("date.format", "yyyyMMdd HH:mm:ss"));
		} catch (IllegalArgumentException e) {
			log.log(Level.SEVERE,
					LOG_PREFIX
							+ "Invalid date format.  Please check the properties file.  For more info on the date format see here: http://goo.gl/YSes");
			return false;
		} catch (NullPointerException e) {
			log.log(Level.SEVERE,
					LOG_PREFIX
							+ "No date format specified.  Please check the properties file. For more info on the date format see here: http://goo.gl/YSes");
			return false;
		}

		staleTimeout = properties.getInt("stale-timeout", 300);
		markersFile = properties.getString("markers", "world/markers.json");
		showSpawn = properties.getBoolean("show.spawn", false);

		String[] filesToCheck = { markersFile };
		for (String f : filesToCheck) {
			try {
				File fileCreator = new File(f);
				if (!fileCreator.exists())
					fileCreator.createNewFile();
				BufferedWriter fout = new BufferedWriter(new FileWriter(f));
				fout.write(markersArray.toString());
				fout.close();
			} catch (IOException e) {
				log.log(Level.SEVERE, LOG_PREFIX + "Exception while creating mapmarkers file.", e);
			}
		}

		try {
			properties.save();
		} catch (Exception e) {
			log.log(Level.SEVERE, LOG_PREFIX + "Exception while saving mapmarkers properties file.", e);
		}

		loadMarkers();

		if (showSpawn) {
			Location spawn = etc.getInstance().getServer().getSpawnLocation();
			setMarker("Spawn", spawn.x, spawn.y, spawn.z, 0);

			writeMarkers();
		}
		return true;

	}

	public void enable() {
		if (load()) {
			log.info(LOG_PREFIX + "Mod Enabled.");
			etc.getInstance().addCommand("/newlabel", "[label] - Adds new label at the current position");
			etc.getInstance().addCommand("/dellabel", "[label] - Deletes label");
		} else {
			log.info(LOG_PREFIX + "Error while loading.");
		}
	}

	public void disable() {
		etc.getInstance().removeCommand("/newlabel");
		etc.getInstance().removeCommand("/dellabel");
		log.info(LOG_PREFIX + "Mod Disabled.");

	}

	public void onLogin(Player player) {
		try {
			setMarker(player.getName(), player.getX(), player.getY(), player.getZ(), 4);
			// Update file
			writeMarkers();
		} catch (Exception e) {

		}

	}

	public synchronized boolean writeMarkers() {
		try {

			// Work out 5 minutes ago

			if (staleTimeout > 0) {
				// Remove stale markers
				Calendar cal = Calendar.getInstance();
				cal.add(Calendar.SECOND, -staleTimeout);
				date = cal.getTime();
				int markerId = 4;

				try {
					for (Object obj : markersArray) {
						try {
							JSONObject marker = (JSONObject) obj;
							markerId = Integer.parseInt((String) marker.get("id"));
							if (markerId == 4) {
								// Only remove player positions
								oldDate = dateFormat.parse((String) marker.get("timestamp"));
								if (oldDate.before(date)) {
									removeMarker((String) marker.get("msg"));
								}
							}
						} catch (java.text.ParseException e) {
							log.log(Level.WARNING,
									LOG_PREFIX
											+ "Unable to parse existing timestamp.  If you changed the format and reloaded the plugin, that is probably the cause.",
									e);
						}
					}
				} catch (Exception e) {

				}
			}

			BufferedWriter fout = new BufferedWriter(new FileWriter(markersFile));
			fout.write(markersArray.toString());
			fout.close();

		} catch (Exception e) {
			log.log(Level.SEVERE, LOG_PREFIX + "Exception while updating label", e);

			return false;
		}

		return true;
	}

	private static int getMarkerIndex(String label) {

		boolean inList = false;
		for (String l : markerList) {
			if (l.equals(label))
				inList = true;
		}

		if (!inList) {
			markerList.add(label);
			markersArray.add(new JSONObject());
		}

		return markerList.indexOf(label);
	}

	public void setMarker(String label, double x, double y, double z, long id) {
		setMarker(label, x, y, z, id, new java.util.Date());
	}

	@SuppressWarnings("unchecked")
	public void setMarker(String label, double x, double y, double z, long id, Date markerDate) {
		int index = getMarkerIndex(label);
		JSONObject newMarker = new JSONObject();
		newMarker.put("msg", label);
		newMarker.put("x", x);
		newMarker.put("y", y);
		newMarker.put("z", z);
		newMarker.put("id", id);
		newMarker.put("timestamp", dateFormat.format(markerDate));
		markersArray.set(index, newMarker);
	}

	public void removeMarker(String label) {
		int index = getMarkerIndex(label);
		markersArray.remove(index);
		markerList.remove(index);
	}

	public void loadMarkers() {
		// !TODO!Load existing markers.json into array
		JSONArray tempmarkersArray = new JSONArray();
		try {
			File inFile = new File(markersFile);
			BufferedReader fin = new BufferedReader(new FileReader(inFile));

			JSONParser parser = new JSONParser();

			try {
				Object obj = parser.parse(fin);

				tempmarkersArray = (JSONArray) obj;

				for (int i = 0; i < tempmarkersArray.size(); i++) {
					try {
						JSONObject marker = (JSONObject) tempmarkersArray.get(i);
						setMarker((String) marker.get("msg"), (Double) marker.get("x"), (Double) marker.get("y"),
								(Double) marker.get("z"), (Long) marker.get("id"),
								dateFormat.parse((String) marker.get("timestamp")));
					} catch (java.text.ParseException e) {
						log.log(Level.WARNING,
								LOG_PREFIX
										+ "Unable to parse existing timestamp.  If you changed the format and reloaded the plugin, that is probably the cause.",
								e);
					}
				}

			} catch (ParseException pe) {
				log.log(Level.SEVERE, LOG_PREFIX + "Exception while parsing line", pe);
			} catch (Exception e) {
				log.log(Level.SEVERE, LOG_PREFIX + "Exception while parsing line", e);
			}

			fin.close();

		} catch (Exception e) {
			log.log(Level.SEVERE, LOG_PREFIX + "Exception while reading markers", e);
		}

	}

	public class MapMarkersListener extends PluginListener {

		public boolean onCommand(Player player, String[] split) {
			if (!player.canUseCommand(split[0]))
				return false;

			if (split[0].equalsIgnoreCase("/newlabel")) {
				// !TODO!add error checking to look for existing labels
				if (split.length < 2) {
					player.sendMessage(Colors.Rose + "Correct usage is: /newlabel [name] ");
					return true;
				}

				int labelId = 3;
				String label = split[1];
				if (split.length >= 2) {
					for (int i = 2; i < split.length; i++)
						label += split[i];
				}

				setMarker(label, player.getX(), player.getY(), player.getZ(), labelId);
				log.info(LOG_PREFIX + player.getName() + " created a new label called " + split[1] + ".");
				player.sendMessage(Colors.Green + "Label Created!");

			} else if (split[0].equalsIgnoreCase("/dellabel")) {
				// !TODO!add error checking to delete only existing labels
				if (split.length < 2) {
					player.sendMessage(Colors.Rose + "Correct usage is: /dellabel [name] ");
					return true;
				}
				String label = split[1];
				if (split.length >= 2) {
					for (int i = 2; i < split.length; i++)
						label += split[i];
				}

				removeMarker(label);

				log.info(LOG_PREFIX + player.getName() + " deleted a label called " + split[1] + ".");
				player.sendMessage(Colors.Green + "Label Deleted!");

			}
			// !TODO!add listlabels
			else {
				return false;
			}
			return true;
		}

		public void onPlayerMove(Player player, Location from, Location to) {
			try {
				setMarker(player.getName(), to.x, to.y, to.z, 4);

				if (available.tryAcquire()) {
					// Update file
					writeMarkers();
					// Set timer to release
					Timer timer = new Timer();
					timer.schedule(new TimerTask() {
						public void run() {
							available.release();
						}
					}, writeInterval * 1000);

				}
			} catch (Exception e) {
				e.printStackTrace();

			}

		}

		public void onDisconnect(Player player) {
			log.info(LOG_PREFIX + "Removing marker for " + player.getName());
			removeMarker(player.getName());
			writeMarkers();
		}

	}

}
