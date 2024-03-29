package Lihad.BeyondCopper;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import net.milkbowl.vault.economy.Economy;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import com.nijiko.permissions.PermissionHandler;
import com.nijikokun.bukkit.Permissions.Permissions;

//TODO: Any area where a Status is specifically written, make dynamic.
// method for rank hierarchy is needed
public class BeyondStatus extends JavaPlugin implements Listener {
	public static FileConfiguration config;
	protected static String PLUGIN_NAME = "BeyondStatus";
	protected static String header = "[" + PLUGIN_NAME + "] ";
	protected static PermissionHandler handler;
	private static Logger log = Logger.getLogger("Minecraft");
	private static Map<String,Status> selection_map = new HashMap<String,Status>();
	private static Map<String,Status> expiry_dump_cache = new HashMap<String,Status>();
	private static Map<String,Long> null_expiry_dump_cache = new HashMap<String,Long>();
	public static List<Status> status_list = new LinkedList<Status>();
	public static Economy econ;

	SimpleDateFormat parserSDF=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss ZZZZZ");


	public class Status{
		String name;
		int cost;
		Map<String,Long> expiration = new HashMap<String,Long>();
		List<Location> locations = new LinkedList<Location>();

		Status(String n, int c, Map<String, Long> m, List<Location> l){name = n; cost = c; expiration = m; locations = l;}
	}
	@Override
	public void onDisable() {
		save();
	}
	@SuppressWarnings("deprecation")
	@Override
	public void onEnable() {
		//load config.yml
		config = getConfig();
		//data loader
		//TODO: check against PEX for groupings?
		for(String iter : config.getStringList("enabled")){
			int v = config.getInt("status."+iter);

			//build origin data
			if(config.getConfigurationSection("data.status."+iter) == null){
				config.set("data.status."+iter+".players", null);
				config.set("data.status."+iter+".locations", null);
			}

			Map<String,Long> m = new HashMap<String,Long>();			
			for(String key : config.getConfigurationSection("data.status."+iter+".players").getKeys(false)){
				m.put(key,config.getLong("data.status."+iter+".players."+key));
			}
			List<String> l_temp = config.getStringList("data.status."+iter+".locations");
			List<Location> l = new LinkedList<Location>();
			while(!l_temp.isEmpty()){
				l.add(toLocation(l_temp.remove(0)));
			}

			if(v == 0){warning("Status: "+iter+" has no assigned value.  Dropping from Status pool.");}
			else{status_list.add(new Status(iter, v, m, l));info("Status: "+iter+" added to Status pool.  Value set to "+v);}
		}
		
		for(String iter : config.getStringList("dump")){
			null_expiry_dump_cache.put(iter, config.getLong(iter));
		}

		//expiry checks
		for(Status status : status_list){
			for(String player_name : status.expiration.keySet()){
				if((System.currentTimeMillis()-status.expiration.get(player_name)) > 2592000000L){
					expiry_dump_cache.put(player_name, status);
				}
			}
		}
		for(String player_name : null_expiry_dump_cache.keySet()){
			if((System.currentTimeMillis()-null_expiry_dump_cache.get(player_name)) > 2592000000L){
				expiry_dump_cache.put(player_name, null);
			}
		}

		//register listeners
		this.getServer().getPluginManager().registerEvents(this, this);

		//setup dependency plugins
		setupPermissions();
		setupEconomy();

		//timer
		this.getServer().getScheduler().scheduleAsyncDelayedTask(this, new Runnable(){
			@Override
			public void run() {
				info("Running expiry cache dump.");
				while(!expiry_dump_cache.isEmpty()) downgrade(expiry_dump_cache.get(expiry_dump_cache.keySet().toArray()[0]), expiry_dump_cache.keySet().toArray()[0].toString());
				info("Ending expiry cache dump.");
			}
		},2400);
	}

	@EventHandler
	public void onPluginEnable(PluginEnableEvent event){
		if((event.getPlugin().getDescription().getName().equals("Permissions"))) setupPermissions();
		if((event.getPlugin().getDescription().getName().equals("Vault"))) setupEconomy();
	}
	
	//WARNING: this tree MUST NOT inherit into the main tree at its top
	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerJoin(PlayerJoinEvent event){
		for(Status status : status_list){
			if(handler.inGroup(event.getPlayer().getName(), status.name)){
				null_expiry_dump_cache.put(event.getPlayer().getName(), System.currentTimeMillis());
				break;
			}
		}
	}

	@EventHandler
	public void onPlayerInteract(PlayerInteractEvent event){
		for(Status status : status_list){
			if(event.getClickedBlock() != null && status.locations.contains(event.getClickedBlock().getLocation())){
				if(handler.inGroup(event.getPlayer().getName(), "Iron") && !handler.inGroup(event.getPlayer().getName(), "Copper")){
					event.getPlayer().sendMessage(ChatColor.RED+"You are Iron Elite or higher, you can't interact with this");
				}else{
					if(econ.bankBalance(event.getPlayer().getName()).balance >= status.cost && !handler.inGroup(event.getPlayer().getName(), status.name)){
						econ.bankWithdraw(event.getPlayer().getName(), status.cost);
						event.getPlayer().sendMessage(ChatColor.DARK_GRAY+"You purchased"+ChatColor.GREEN+status.name+ChatColor.DARK_GRAY+" status!  It will expire in 30 days!");
						upgrade(status, event.getPlayer());
					}else if(handler.inGroup(event.getPlayer().getName(), "Copper")){
						event.getPlayer().sendMessage(ChatColor.RED+"You are already"+status.name+"Elite!");
					}else{
						event.getPlayer().sendMessage(ChatColor.RED+"You do not have enough moneys to purchase Copper status");
					}
				}
			}
		}
		if(event.getClickedBlock() != null && selection_map.containsKey(event.getPlayer().getName())){
			if(selection_map.get(event.getPlayer().getName()) == null){
				for(Status status : status_list){
					if(status.locations.contains(event.getClickedBlock().getLocation())){
						status.locations.remove(event.getClickedBlock().getLocation());
						event.getPlayer().sendMessage(ChatColor.AQUA+status.name+" location removed");
					}
				}
			}else{
				selection_map.get(event.getPlayer().getName()).locations.add(event.getClickedBlock().getLocation());
				event.getPlayer().sendMessage(ChatColor.GREEN+selection_map.get(event.getPlayer().getName()).name+" location set");
			}

			selection_map.remove(event.getPlayer().getName());
			event.getPlayer().sendMessage(ChatColor.GRAY+"Status selection tool de-selected.");
		}
	}

	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		Player player = (Player)sender;
		if(cmd.getName().equalsIgnoreCase("bstat")){
			if(args.length == 0){
				if(player.isOp()){
					player.sendMessage(ChatColor.GOLD+"-------Admin");
					player.sendMessage(ChatColor.GRAY+"./bstat set <status|null>");
					player.sendMessage(ChatColor.GRAY+"./bstat remove");
				}
				player.sendMessage(ChatColor.AQUA+"-------Status");
				for(Status status : status_list){
					if(status.expiration.containsKey(player.getName())){
						Calendar c = Calendar.getInstance();
						c.setTimeInMillis(status.expiration.get(player.getName())+2592000000L);
						player.sendMessage("Your elite status expires on "+parserSDF.format(c.getTime()));
						return true;
					}
				}
				player.sendMessage("No status found....");
			}else if(args.length == 1 && player.isOp()){
				if(args[0].equalsIgnoreCase("set"))player.sendMessage(ChatColor.GRAY+"./bstat set <status|null>");
				else if(args[0].equalsIgnoreCase("remove")){
					selection_map.put(player.getName(), null);
					player.sendMessage(ChatColor.GREEN+"Remove loaded.  Next clicked block will have Status removed.");
				}else{
					player.sendMessage(ChatColor.GRAY+"./bstat");
				}
			}else if(args.length == 2 && player.isOp()){
				if(args[0].equalsIgnoreCase("remove"))player.sendMessage(ChatColor.GRAY+"./bstat remove");
				else if(args[0].equalsIgnoreCase("set")){
					Status status = null;
					String str = "null";
					for(Status s : status_list){
						if(s.name.equalsIgnoreCase(args[1])){
							status = s;
							str = status.name;
						}else{
							player.sendMessage(ChatColor.RED+"Invalid Status.  Set as NULL (delete)");
						}
					}
					selection_map.put(player.getName(), status);
					player.sendMessage(ChatColor.GREEN+"Add loaded.  Next clicked block will have the selected status: "+str);
				}else{
					player.sendMessage(ChatColor.GRAY+"./bstat");
				}
			}else{
				player.sendMessage(ChatColor.GRAY+"./bstat");
			}
			return true;
		}
		return false;
	}
	public void upgrade(Status status, Player player){
		getServer().dispatchCommand(getServer().getConsoleSender(), "pex user "+player.getName()+" group set "+status.name);
		status.expiration.put(player.getName(),System.currentTimeMillis());
	}
	//TODO: make dynamic.  let Explorer be a set group
	public void downgrade(Status status, String player_name){
		getServer().dispatchCommand(getServer().getConsoleSender(), "pex user "+player_name+" group set Explorer");
		if(status == null) null_expiry_dump_cache.remove(player_name);
		else status.expiration.remove(player_name);
	}

	protected void save(){
		for(Status status : status_list){
			config.set("data.status."+status.name+".players", status.expiration);
			List<String> s = new LinkedList<String>();
			List<Location> s_local_temp = new LinkedList<Location>(status.locations);
			while(!s_local_temp.isEmpty()){
				s.add(toString(s_local_temp.remove(0)));
			}
			config.set("data.status."+status.name+".locations", status.locations);
		}
		config.set("dump", null_expiry_dump_cache);
		this.saveConfig();
	}
	private boolean setupEconomy() {
		if (getServer().getPluginManager().getPlugin("Vault") == null) {
			return false;
		}
		RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
		if (rsp == null) {
			return false;
		}
		econ = rsp.getProvider();
		return econ != null;
	}
	private void setupPermissions() {
		Plugin permissionsPlugin = Bukkit.getServer().getPluginManager().getPlugin("Permissions");
		if (permissionsPlugin != null) {
			info("Succesfully connected to Permissions!");
			handler = ((Permissions) permissionsPlugin).getHandler();
		} else {
			handler = null;
			warning("Disconnected from Permissions...what could possibly go wrong?");
		}
	}
	protected static Location toLocation(String string){
		String[] array;
		if(string == null) return null;
		array = string.split(",");
		Location location = new Location(org.bukkit.Bukkit.getServer().getWorld(array[3]), Integer.parseInt(array[0]), Integer.parseInt(array[1]), Integer.parseInt(array[2]));
		return location;
	}
	protected static String toString(Location location){
		if(location == null) return null;
		return (location.getBlockX()+","+location.getBlockY()+","+location.getBlockZ()+","+location.getWorld().getName());
	}
	private static void info(String message){ 
		log.info(header + ChatColor.WHITE + message);
	}
	private static void severe(String message){
		log.severe(header + ChatColor.RED + message);
	}
	private static void warning(String message){
		log.warning(header + ChatColor.YELLOW + message);
	}
	public static void log(java.util.logging.Level level, String message){
		log.log(level, header + message);
	}
}