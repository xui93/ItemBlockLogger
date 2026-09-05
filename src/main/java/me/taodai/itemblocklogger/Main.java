package me.taodai.itemblocklogger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.NamespacedKey;

import java.sql.*;
import java.time.Instant;
import java.util.Base64;

public class Main extends JavaPlugin implements Listener {

    private Connection connection;
    private NamespacedKey dropIdKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        dropIdKey = new NamespacedKey(this, "drop-id");

        try {
            getDataFolder().mkdirs();

            connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + getDataFolder() + "/logs.db"
            );

            createTables();

        } catch (SQLException e) {
            getLogger().severe("Khong the mo SQLite!");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        getCommand("itemlog").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) return true;

            if (args.length == 0) {
                player.sendMessage("§c/itemlog <player|near>");
                return true;
            }

            if (args[0].equalsIgnoreCase("near")) {
                showNearbyItems(player);
            } else {
                showPlayerItems(player, args[0]);
            }

            return true;
        });

        getCommand("itemrestore").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) return true;

            if (args.length != 1) {
                player.sendMessage("§c/itemrestore <id>");
                return true;
            }

            try {
                restoreItem(player, Long.parseLong(args[0]));
            } catch (NumberFormatException e) {
                player.sendMessage("§cID khong hop le.");
            }

            return true;
        });

        getCommand("blocklog").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) return true;

            if (args.length == 0) {
                player.sendMessage("§c/blocklog <player|near>");
                return true;
            }

            if (args[0].equalsIgnoreCase("near")) {
                showNearbyBlocks(player);
            } else {
                showPlayerBlocks(player, args[0]);
            }

            return true;
        });

        getLogger().info("ItemBlockLogger da bat!");
    }

    private void createTables() throws SQLException {

        try (Statement st = connection.createStatement()) {

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS item_drops (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT,
                    player TEXT,
                    world TEXT,
                    x DOUBLE,
                    y DOUBLE,
                    z DOUBLE,
                    item TEXT,
                    time INTEGER,
                    status TEXT
                )
            """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS block_breaks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT,
                    player TEXT,
                    world TEXT,
                    x DOUBLE,
                    y DOUBLE,
                    z DOUBLE,
                    block TEXT,
                    time INTEGER
                )
            """);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {

        if (!getConfig().getBoolean("log-item-drops", true)) return;

        Player player = event.getPlayer();
        Item entity = event.getItemDrop();
        ItemStack stack = entity.getItemStack();

        try {

            String encoded = Base64.getEncoder().encodeToString(
                    stack.serializeAsBytes()
            );

            Location loc = entity.getLocation();

            try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO item_drops
                (uuid, player, world, x, y, z, item, time, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, Statement.RETURN_GENERATED_KEYS)) {

                ps.setString(1, player.getUniqueId().toString());
                ps.setString(2, player.getName());
                ps.setString(3, loc.getWorld().getName());
                ps.setDouble(4, loc.getX());
                ps.setDouble(5, loc.getY());
                ps.setDouble(6, loc.getZ());
                ps.setString(7, encoded);
                ps.setLong(8, Instant.now().getEpochSecond());
                ps.setString(9, "DROPPED");

                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        long id = rs.getLong(1);

                        entity.getPersistentDataContainer().set(
                                dropIdKey,
                                PersistentDataType.LONG,
                                id
                        );
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {

        Item item = event.getItem();

        Long id = item.getPersistentDataContainer().get(
                dropIdKey,
                PersistentDataType.LONG
        );

        if (id == null) return;

        updateItemStatus(id, "PICKED_UP");
    }

    @EventHandler
    public void onDespawn(ItemDespawnEvent event) {

        Item item = event.getEntity();

        Long id = item.getPersistentDataContainer().get(
                dropIdKey,
                PersistentDataType.LONG
        );

        if (id == null) return;

        updateItemStatus(id, "DESPAWNED");
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {

        if (!getConfig().getBoolean("log-block-breaks", true)) return;

        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location loc = block.getLocation();

        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO block_breaks
            (uuid, player, world, x, y, z, block, time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """)) {

            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, player.getName());
            ps.setString(3, loc.getWorld().getName());
            ps.setDouble(4, loc.getX());
            ps.setDouble(5, loc.getY());
            ps.setDouble(6, loc.getZ());
            ps.setString(7, block.getType().name());
            ps.setLong(8, Instant.now().getEpochSecond());

            ps.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void updateItemStatus(long id, String status) {

        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE item_drops SET status=? WHERE id=?"
        )) {

            ps.setString(1, status);
            ps.setLong(2, id);
            ps.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void showNearbyItems(Player player) {

        double radius = getConfig().getDouble("near-radius", 10);
        int max = getConfig().getInt("max-results", 20);

        Location loc = player.getLocation();

        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT id, player, world, x, y, z, time, status
            FROM item_drops
            WHERE world = ?
            ORDER BY id DESC
            LIMIT ?
        """)) {

            ps.setString(1, loc.getWorld().getName());
            ps.setInt(2, max);

            try (ResultSet rs = ps.executeQuery()) {

                player.sendMessage("§6§lItem drops gan day:");

                while (rs.next()) {

                    double x = rs.getDouble("x");
                    double y = rs.getDouble("y");
                    double z = rs.getDouble("z");

                    Location dropLoc = new Location(
                            loc.getWorld(), x, y, z
                    );

                    if (dropLoc.distance(loc) <= radius) {

                        player.sendMessage(
                                "§e#" + rs.getLong("id") +
                                " §f" + rs.getString("player") +
                                " §7" + rs.getString("status") +
                                " §8(" +
                                (int)x + ", " +
                                (int)y + ", " +
                                (int)z + ")"
                        );
                    }
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void showPlayerItems(Player player, String name) {

        int max = getConfig().getInt("max-results", 20);

        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT id, player, world, x, y, z, time, status
            FROM item_drops
            WHERE player = ?
            ORDER BY id DESC
            LIMIT ?
        """)) {

            ps.setString(1, name);
            ps.setInt(2, max);

            try (ResultSet rs = ps.executeQuery()) {

                player.sendMessage("§6§lItem drops cua " + name + ":");

                while (rs.next()) {

                    player.sendMessage(
                            "§e#" + rs.getLong("id") +
                            " §7" + rs.getString("status") +
                            " §8" +
                            rs.getString("world") +
                            " (" +
                            (int)rs.getDouble("x") + ", " +
                            (int)rs.getDouble("y") + ", " +
                            (int)rs.getDouble("z") + ")"
                    );
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void restoreItem(Player player, long id) {

        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT world, x, y, z, item, status
            FROM item_drops
            WHERE id = ?
        """)) {

            ps.setLong(1, id);

            try (ResultSet rs = ps.executeQuery()) {

                if (!rs.next()) {
                    player.sendMessage("§cKhong tim thay item #" + id);
                    return;
                }

                String worldName = rs.getString("world");
                World world = Bukkit.getWorld(worldName);

                if (world == null) {
                    player.sendMessage("§cWorld khong ton tai.");
                    return;
                }

                ItemStack item = ItemStack.deserializeBytes(
                        Base64.getDecoder().decode(
                                rs.getString("item")
                        )
                );

                Location loc = new Location(
                        world,
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z")
                );

                Item dropped = world.dropItem(loc, item);

                dropped.getPersistentDataContainer().set(
                        dropIdKey,
                        PersistentDataType.LONG,
                        id
                );

                updateItemStatus(id, "RESTORED");

                player.sendMessage(
                        "§aDa restore item #" + id
                );
            }

        } catch (Exception e) {
            player.sendMessage("§cLoi khi restore item.");
            e.printStackTrace();
        }
    }

    private void showNearbyBlocks(Player player) {

        double radius = getConfig().getDouble("near-radius", 10);
        int max = getConfig().getInt("max-results", 20);

        Location loc = player.getLocation();

        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT id, player, world, x, y, z, block, time
            FROM block_breaks
            WHERE world = ?
            ORDER BY id DESC
            LIMIT ?
        """)) {

            ps.setString(1, loc.getWorld().getName());
            ps.setInt(2, max);

            try (ResultSet rs = ps.executeQuery()) {

                player.sendMessage("§6§lBlock break gan day:");

                while (rs.next()) {

                    Location blockLoc = new Location(
                            loc.getWorld(),
                            rs.getDouble("x"),
                            rs.getDouble("y"),
                            rs.getDouble("z")
                    );

                    if (blockLoc.distance(loc) <= radius) {

                        player.sendMessage(
                                "§e#" + rs.getLong("id") +
                                " §f" + rs.getString("player") +
                                " §7pha " +
                                rs.getString("block") +
                                " §8(" +
                                (int)rs.getDouble("x") + ", " +
                                (int)rs.getDouble("y") + ", " +
                                (int)rs.getDouble("z") + ")"
                        );
                    }
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void showPlayerBlocks(Player player, String name) {

        int max = getConfig().getInt("max-results", 20);

        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT id, player, world, x, y, z, block, time
            FROM block_breaks
            WHERE player = ?
            ORDER BY id DESC
            LIMIT ?
        """)) {

            ps.setString(1, name);
            ps.setInt(2, max);

            try (ResultSet rs = ps.executeQuery()) {

                player.sendMessage("§6§lBlock break cua " + name + ":");

                while (rs.next()) {

                    player.sendMessage(
                            "§e#" + rs.getLong("id") +
                            " §7pha " +
                            rs.getString("block") +
                            " §8" +
                            rs.getString("world") +
                            " (" +
                            (int)rs.getDouble("x") + ", " +
                            (int)rs.getDouble("y") + ", " +
                            (int)rs.getDouble("z") + ")"
                    );
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {

        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
