package com.nukkitx.server.proxy;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.nukkitx.server.proxy.command.SpecificServerCommandExecutor;
import com.nukkitx.server.proxy.storage.CompactUUIDSerializer;
import com.nukkitx.server.proxy.storage.KeyHasherName;
import com.nukkitx.server.proxy.storage.KeyHasherUUID;
import com.nukkitx.server.proxy.storage.PlayerData;
import com.nukkitx.server.proxy.storage.ServerData;
import lombok.NonNull;
import net.daporkchop.lib.binary.stream.DataOut;
import net.daporkchop.lib.config.PConfig;
import net.daporkchop.lib.config.decoder.PorkConfigDecoder;
import net.daporkchop.lib.db.PorkDB;
import net.daporkchop.lib.db.container.map.DBMap;
import net.daporkchop.lib.db.container.map.data.ConstantLengthLookup;
import net.daporkchop.lib.db.container.map.data.SectoredDataLookup;
import net.daporkchop.lib.db.container.map.index.hashtable.BucketingHashTableIndexLookup;
import net.daporkchop.lib.db.container.map.index.tree.FasterTreeIndexLookup;
import net.daporkchop.lib.db.container.map.key.DefaultKeyHasher;
import net.daporkchop.lib.encoding.compression.Compression;
import net.daporkchop.lib.hash.util.Digest;
import net.daporkchop.lib.nbt.util.IndirectNBTSerializer;
import org.itxtech.nemisys.Client;
import org.itxtech.nemisys.Player;
import org.itxtech.nemisys.command.PluginCommand;
import org.itxtech.nemisys.plugin.PluginBase;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * @author DaPorkchop_
 */
public class ProxyMain extends PluginBase {
    public static ProxyMain INSTANCE;

    public PorkDB db;

    private DBMap<UUID, PlayerData> playerDataMap;
    public LoadingCache<UUID, PlayerData> playerData;
    private DBMap<String, UUID> playerNameLookupMap;
    public LoadingCache<String, UUID> playerNameLookup;
    private DBMap<String, ServerData> serverDataMap;
    public LoadingCache<String, ServerData> serverData;

    @Override
    public void onEnable() {
        INSTANCE = this;
        this.onReload();

        this.getLogger().info("Opening databases...");
        try {
            this.db = PorkDB.builder(new File(this.getDataFolder(), "database")).build();

            this.playerDataMap = this.db.<UUID, PlayerData>map("playerData")
                    .setCompression(Compression.GZIP_NORMAL)
                    .setIndexLookup(new FasterTreeIndexLookup<>(4, 1))
                    .setDataLookup(new SectoredDataLookup(1024))
                    .setKeyHasher(new KeyHasherUUID())
                    .setValueSerializer(new IndirectNBTSerializer<>(PlayerData::new))
                    .build();
            this.playerData = this.cached(this.playerDataMap, PlayerData::new);

            this.playerNameLookupMap = this.db.<String, UUID>map("playerNames")
                    .setIndexLookup(new FasterTreeIndexLookup<>(4, 1))
                    .setDataLookup(new ConstantLengthLookup())
                    .setKeyHasher(new DefaultKeyHasher<>(Digest.SHA_256))
                    .setKeyHasher(new KeyHasherName(16))
                    .setValueSerializer(new CompactUUIDSerializer())
                    .build();
            this.playerNameLookup = this.cached(this.playerNameLookupMap, () -> new UUID(0L, 0L));

            this.serverDataMap = this.db.<String, ServerData>map("serverData")
                    .setCompression(Compression.GZIP_NORMAL)
                    .setIndexLookup(new BucketingHashTableIndexLookup<>(16, 4))
                    .setDataLookup(new SectoredDataLookup(1024))
                    .setKeyHasher(new KeyHasherName(32))
                    .setValueSerializer(new IndirectNBTSerializer<>(ServerData::new))
                    .build();
            this.serverData = this.cached(this.serverDataMap, ServerData::new);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.getLogger().info("Databases opened!");

        {
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(TimeUnit.HOURS.toMillis(1));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    this.onReload();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        this.getServer().getPluginManager().registerEvents(new ProxyListener(), this);

        ((PluginCommand) this.getServer().getCommandMap().getCommand("hub")).setExecutor(new SpecificServerCommandExecutor("hub"));
        ((PluginCommand) this.getServer().getCommandMap().getCommand("server")).setExecutor((sender, command, s, strings) -> {
            if (!sender.isPlayer()) {
                sender.sendMessage("§4Not a player!");
                return true;
            } else if (strings.length < 1) {
                sender.sendMessage("§cTarget server required!");
                return true;
            }
            Client client = this.getServer().getClientByDesc(strings[0].toLowerCase());
            if (client == null) {
                sender.sendMessage(String.format("§cInvalid target server: \"%s\"", strings[0].toLowerCase()));
            } else {
                ((Player) sender).transfer(client);
                sender.sendMessage(String.format("§aConnecting to \"%s\"...", strings[0].toLowerCase()));
            }
            return true;
        });
        ((PluginCommand) this.getServer().getCommandMap().getCommand("proxy")).setExecutor((sender, command, s, strings) -> {
            if (sender.isPlayer() && !this.playerData.getUnchecked(((Player) sender).getUuid()).isAdmin()) {
                sender.sendMessage("§4Missing permission!");
                return true;
            }
            if (strings.length < 1) {
                sender.sendMessage("§cAction required!");
                return true;
            }
            switch (strings[0]) {
                case "reload":
                    sender.sendMessage("§aReloading config...");
                    this.onReload();
                    sender.sendMessage("§aReloaded.");
                    break;
                case "op":
                case "deop":
                    if (strings.length < 2) {
                        sender.sendMessage("§cUsername required!");
                        return true;
                    }
                    UUID uuid;
                    Player player = this.getServer().getPlayer(strings[1]);
                    if (player == null) {
                        uuid = this.playerNameLookup.getUnchecked(strings[1]);
                        if (uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() == 0L) {
                            uuid = null;
                        }
                    } else {
                        uuid = player.getUuid();
                    }
                    if (uuid == null) {
                        sender.sendMessage(String.format("§cCouldn't find UUID for player: \"%s\"", strings[1]));
                        return true;
                    }
                    this.playerData.getUnchecked(uuid).setAdmin(strings[0].charAt(0) == 'o');
                    sender.sendMessage(String.format("§aPlayer \"%s\" %sopped!", strings[1], strings[0].charAt(0) == 'o' ? "" : "de"));
                    break;
                default:
                    sender.sendMessage(String.format("§cUnknown action: \"%s\"", strings[0]));
            }
            return true;
        });
    }

    @Override
    public void onDisable() {
        this.getLogger().info("Closing databases...");
        try {
            this.playerData.invalidateAll();
            this.playerNameLookup.invalidateAll();
            this.serverData.invalidateAll();

            this.db.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.getLogger().info("Databases closed!");
    }

    public void onReload() {
        try {
            File configFile = new File(this.getDataFolder(), "config.cfg");
            boolean fresh = !configFile.exists();
            if (fresh) {
                configFile.getParentFile().mkdirs();
                configFile.createNewFile();
            }
            new PConfig(new PorkConfigDecoder()).load(ProxyConfig.class, configFile);
            if (fresh) {
                this.getLogger().debug("Writing fresh config...");
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                new PConfig(new PorkConfigDecoder()).save(ProxyConfig.INSTANCE, DataOut.wrap(baos));
                this.getLogger().debug("Config written! " + baos.size() + " bytes");
                try (DataOut out = DataOut.wrap(configFile)) {
                    out.write(baos.toByteArray());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.getLogger().info(ProxyConfig.INSTANCE.joinMessage);
    }

    private <K, V> LoadingCache<K, V> cached(@NonNull DBMap<K, V> map, @NonNull Supplier<V> emptyValueSupplier) {
        return CacheBuilder.newBuilder()
                .maximumSize(256L)
                .expireAfterAccess(1, TimeUnit.HOURS)
                .removalListener((RemovalListener<K, V>) notification -> {
                    if (notification.getValue() instanceof UUID && ((UUID) notification.getValue()).getMostSignificantBits() == 0L && ((UUID) notification.getValue()).getLeastSignificantBits() == 0L) {
                        return; //don't save placeholder uuids
                    }
                    map.put(notification.getKey(), notification.getValue());
                })
                .build(new CacheLoader<K, V>() {
                    @Override
                    public V load(K key) throws Exception {
                        V val = map.get(key);
                        return val == null ? emptyValueSupplier.get() : val;
                    }
                });
    }
}
