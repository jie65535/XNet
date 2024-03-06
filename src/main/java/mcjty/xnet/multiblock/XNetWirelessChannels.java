package mcjty.xnet.multiblock;

import lombok.Getter;
import lombok.Setter;
import mcjty.lib.varia.BlockPosTools;
import mcjty.lib.varia.LevelTools;
import mcjty.lib.worlddata.AbstractWorldData;
import mcjty.rftoolsbase.api.xnet.channels.IChannelType;
import mcjty.rftoolsbase.api.xnet.keys.NetworkId;
import mcjty.xnet.XNet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static mcjty.xnet.apiimpl.Constants.TAG_AGE;
import static mcjty.xnet.apiimpl.Constants.TAG_CHANNELS;
import static mcjty.xnet.apiimpl.Constants.TAG_DIM;
import static mcjty.xnet.apiimpl.Constants.TAG_NAME;
import static mcjty.xnet.apiimpl.Constants.TAG_NETWORK;
import static mcjty.xnet.apiimpl.Constants.TAG_OWNER;
import static mcjty.xnet.apiimpl.Constants.TAG_ROUTERS;
import static mcjty.xnet.apiimpl.Constants.TAG_TYPE;

public class XNetWirelessChannels extends AbstractWorldData<XNetWirelessChannels> {

    private static final String NAME = "XNetWirelessChannels";

    private final Map<WirelessChannelKey, WirelessChannelInfo> channelToWireless = new HashMap<>();

    public XNetWirelessChannels() {
    }

    public XNetWirelessChannels(CompoundTag tag) {
        channelToWireless.clear();
        ListTag tagList = tag.getList(TAG_CHANNELS, Tag.TAG_COMPOUND);
        for (int i = 0 ; i < tagList.size() ; i++) {
            CompoundTag tc = tagList.getCompound(i);
            WirelessChannelInfo channelInfo = new WirelessChannelInfo();
            readRouters(tc.getList(TAG_ROUTERS, Tag.TAG_COMPOUND), channelInfo);
            UUID owner = null;
            if (tc.hasUUID(TAG_OWNER)) {
                owner = tc.getUUID(TAG_OWNER);
            }
            String name = tc.getString(TAG_NAME);
            IChannelType type = XNet.xNetApi.findType(tc.getString(TAG_TYPE));
            channelToWireless.put(new WirelessChannelKey(name, type, owner), channelInfo);
        }
    }

    @Getter
    private int globalChannelVersion = 1;

    public void transmitChannel(String channel, @Nonnull IChannelType channelType, @Nullable UUID ownerUUID, ResourceKey<Level> dimension, BlockPos wirelessRouterPos, NetworkId network) {
        WirelessChannelInfo channelInfo;
        WirelessChannelKey key = new WirelessChannelKey(channel, channelType, ownerUUID);
        if (channelToWireless.containsKey(key)) {
            channelInfo = channelToWireless.get(key);
        } else {
            channelInfo = new WirelessChannelInfo();
            channelToWireless.put(key, channelInfo);
        }

        GlobalPos pos = GlobalPos.of(dimension, wirelessRouterPos);
        WirelessRouterInfo info = channelInfo.getRouter(pos);
        if (info == null) {
            info = new WirelessRouterInfo(pos);
            channelInfo.updateRouterInfo(pos, info);

            // Global version increase to make sure all wireless routers can detect if there is a need
            // to make their local network dirty to ensure that all controllers connected to that network
            // get a chance to pick up the new transmitted channel
            updateGlobalChannelVersion();
        }
        info.setAge(0);
        info.setNetworkId(network);
        save();

//        cnt--;
//        if (cnt > 0) {
//            return;
//        }
//        cnt = 30;
//        dump();
    }

    public void updateGlobalChannelVersion() {
        globalChannelVersion++;
    }

    private void dump() {
        for (Map.Entry<WirelessChannelKey, WirelessChannelInfo> entry : channelToWireless.entrySet()) {
            System.out.println("Channel = " + entry.getKey());
            WirelessChannelInfo channelInfo = entry.getValue();
            for (Map.Entry<GlobalPos, WirelessRouterInfo> infoEntry : channelInfo.getRouters().entrySet()) {
                GlobalPos pos = infoEntry.getKey();
                WirelessRouterInfo info = infoEntry.getValue();
                System.out.println("    Pos = " + BlockPosTools.toString(pos.pos()) + " (age " + info.age + ", net " + info.networkId.id() + ")");
            }
        }
    }

    public void tick(Level world, int amount) {
        if (channelToWireless.isEmpty()) {
            return;
        }

        XNetBlobData blobData = XNetBlobData.get(world);

        Set<WirelessChannelKey> toDeleteChannel = new HashSet<>();
        for (Map.Entry<WirelessChannelKey, WirelessChannelInfo> entry : channelToWireless.entrySet()) {
            WirelessChannelInfo channelInfo = entry.getValue();
            Set<GlobalPos> toDelete = new HashSet<>();
            for (Map.Entry<GlobalPos, WirelessRouterInfo> infoEntry : channelInfo.getRouters().entrySet()) {
                WirelessRouterInfo info = infoEntry.getValue();
                int age = info.getAge();
                age += amount;
                info.setAge(age);
                if (age > 40) { // @todo configurable
                    toDelete.add(infoEntry.getKey());
                }
            }
            for (GlobalPos pos : toDelete) {
                WorldBlob worldBlob = blobData.getWorldBlob(pos.dimension());
                NetworkId networkId = channelInfo.getRouter(pos).getNetworkId();
//                System.out.println("Clean up wireless network = " + networkId + " (" + entry.getKey() + ")");
                worldBlob.markNetworkDirty(networkId);
                channelInfo.removeRouterInfo(pos);
                XNetWirelessChannels.get(world).updateGlobalChannelVersion();
            }
            if (channelInfo.getRouters().isEmpty()) {
                toDeleteChannel.add(entry.getKey());
            }
        }

        if (!toDeleteChannel.isEmpty()) {
            for (WirelessChannelKey key : toDeleteChannel) {
                channelToWireless.remove(key);
            }
        }

        save();
    }

    @Nonnull
    public static XNetWirelessChannels get(Level world) {
        return getData(world, XNetWirelessChannels::new, XNetWirelessChannels::new, NAME);
    }

    public WirelessChannelInfo findChannel(String name, @Nonnull IChannelType channelType, @Nullable UUID owner) {
        WirelessChannelKey key = new WirelessChannelKey(name, channelType, owner);
        return findChannel(key);
    }

    public WirelessChannelInfo findChannel(WirelessChannelKey key) {
        return channelToWireless.get(key);
    }

    public void forEachChannel(@Nullable UUID owner, Consumer<WirelessChannelInfo> consumer) {
        channelToWireless.forEach((key, info) -> {
            if ((owner == null && key.owner() == null) || (owner != null && (key.owner() == null || owner.equals(key.owner())))) {
                consumer.accept(info);
            }
        });
    }

    private void readRouters(ListTag tagList, WirelessChannelInfo channelInfo) {
        for (int i = 0 ; i < tagList.size() ; i++) {
            CompoundTag tc = tagList.getCompound(i);
            ResourceKey<Level> dim = LevelTools.getId(tc.getString(TAG_DIM));
            GlobalPos pos = GlobalPos.of(dim, new BlockPos(tc.getInt("x"), tc.getInt("y"), tc.getInt("z")));
            WirelessRouterInfo info = new WirelessRouterInfo(pos);
            info.setAge(tc.getInt(TAG_AGE));
            info.setNetworkId(new NetworkId(tc.getInt(TAG_NETWORK)));
            channelInfo.updateRouterInfo(pos, info);
        }
    }

    @Nonnull
    @Override
    public CompoundTag save(@Nonnull CompoundTag compound) {
        ListTag channelTagList = new ListTag();

        for (Map.Entry<WirelessChannelKey, WirelessChannelInfo> entry : channelToWireless.entrySet()) {
            CompoundTag channelTc = new CompoundTag();
            WirelessChannelInfo channelInfo = entry.getValue();
            WirelessChannelKey key = entry.getKey();
            channelTc.putString(TAG_NAME, key.name());
            channelTc.putString(TAG_TYPE, key.channelType().getID());
            if (key.owner() != null) {
                channelTc.putUUID(TAG_OWNER, key.owner());
            }
            channelTc.put(TAG_ROUTERS, writeRouters(channelInfo));
            channelTagList.add(channelTc);
        }

        compound.put(TAG_CHANNELS, channelTagList);

        return compound;
    }

    private ListTag writeRouters(WirelessChannelInfo channelInfo) {
        ListTag tagList = new ListTag();

        for (Map.Entry<GlobalPos, WirelessRouterInfo> infoEntry : channelInfo.getRouters().entrySet()) {
            CompoundTag tc = new CompoundTag();
            GlobalPos pos = infoEntry.getKey();
            tc.putString(TAG_DIM, pos.dimension().location().toString());
            tc.putInt("x", pos.pos().getX());
            tc.putInt("y", pos.pos().getY());
            tc.putInt("z", pos.pos().getZ());
            WirelessRouterInfo info = infoEntry.getValue();
            tc.putInt(TAG_AGE, info.getAge());
            tc.putInt(TAG_NETWORK, info.getNetworkId().id());
            tagList.add(tc);
        }
        return tagList;
    }

    @Getter
    public static class WirelessChannelInfo {
        private final Map<GlobalPos, WirelessRouterInfo> routers = new HashMap<>();

        public void updateRouterInfo(GlobalPos pos, WirelessRouterInfo info) {
            routers.put(pos, info);
        }

        public void removeRouterInfo(GlobalPos pos) {
            routers.remove(pos);
        }

        public WirelessRouterInfo getRouter(GlobalPos pos) {
            return routers.get(pos);
        }

    }

    @Getter
    public static class WirelessRouterInfo {
        @Setter
        private int age;
        @Setter
        private NetworkId networkId;
        private final GlobalPos coordinate;

        public WirelessRouterInfo(GlobalPos coordinate) {
            age = 0;
            this.coordinate = coordinate;
        }

    }
}
