/*
 * Copyright (c) 2009 - 2018 Deutsches Elektronen-Synchroton,
 * Member of the Helmholtz Association, (DESY), HAMBURG, GERMANY
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Library General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this program (see the file COPYING.LIB for more
 * details); if not, write to the Free Software Foundation, Inc.,
 * 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.dcache.nfs;

import org.dcache.nfs.v4.xdr.layout4;
import org.dcache.nfs.v4.xdr.stateid4;
import org.dcache.nfs.v4.xdr.nfs_fh4;
import org.dcache.nfs.v4.xdr.deviceid4;
import org.dcache.nfs.v4.xdr.nfs4_prot;
import org.dcache.nfs.v4.xdr.device_addr4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.EnumMap;
import org.dcache.nfs.status.NoEntException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.cache.Cache;
import javax.cache.Caching;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.dcache.nfs.status.LayoutUnavailableException;
import org.dcache.nfs.status.UnknownLayoutTypeException;
import org.dcache.nfs.v4.CompoundContext;
import org.dcache.nfs.v4.FlexFileLayoutDriver;
import org.dcache.nfs.v4.Layout;
import org.dcache.nfs.v4.LayoutDriver;
import org.dcache.nfs.v4.NFS4Client;
import org.dcache.nfs.v4.NFS4State;
import org.dcache.nfs.v4.NFSv41DeviceManager;
import org.dcache.nfs.v4.NFSv4Defaults;
import org.dcache.nfs.v4.NfsV41FileLayoutDriver;
import org.dcache.nfs.v4.xdr.layouttype4;
import org.dcache.nfs.v4.xdr.length4;
import org.dcache.nfs.v4.xdr.offset4;
import org.dcache.nfs.v4.xdr.utf8str_mixed;
import org.dcache.nfs.vfs.Inode;
import org.dcache.nfs.zk.Paths;
import org.dcache.nfs.zk.ZkDataServer;
import org.dcache.utils.Bytes;

/**
 *
 * the instance of this class have to ask Pool Manager for a pool and return it
 * to the client.
 *
 */

public class DeviceManager implements NFSv41DeviceManager {

    private static final Logger _log = LoggerFactory.getLogger(DeviceManager.class);

    private final Map<deviceid4, InetSocketAddress[]> _deviceMap =
            new ConcurrentHashMap<>();

    // we need to return same layout stateid, as long as it's not returned
    private final Map<stateid4, NFS4State> _openToLayoutStateid = new ConcurrentHashMap<>();

    /**
     * Zookeeper client.
     */
    private CuratorFramework zkCurator;

    /**
     * Path cache to node with all on-line DSes.
     */
    private PathChildrenCache dsNodeCache;

    /**
     * Layout type specific driver.
     */
    private final Map<layouttype4, LayoutDriver> _supportedDrivers;

    // we use 'other' part of stateid as sequence number can change
    private Cache<byte[], byte[]> mdsStateIdCache;


    public DeviceManager() {
        _supportedDrivers = new EnumMap<>(layouttype4.class);
        _supportedDrivers.put(layouttype4.LAYOUT4_FLEX_FILES, new FlexFileLayoutDriver(4, 1,
                new utf8str_mixed("17"),new utf8str_mixed("17"), x -> {})
        );
        _supportedDrivers.put(layouttype4.LAYOUT4_NFSV4_1_FILES, new NfsV41FileLayoutDriver());
    }

    public void setCuratorFramework(CuratorFramework curatorFramework) {
        zkCurator = curatorFramework;
    }

    public void init() throws Exception {

        mdsStateIdCache = Caching
                .getCachingProvider()
                .getCacheManager()
                .getCache("open-stateid", byte[].class, byte[].class);

        dsNodeCache = new PathChildrenCache(zkCurator, Paths.ZK_PATH, true);
        dsNodeCache.getListenable().addListener((c, e) -> {

            switch(e.getType()) {
                case CHILD_ADDED:
                case CHILD_UPDATED:
                    _log.info("Adding DS: {}", e.getData().getPath());
                    addDS(e.getData().getPath());
                    break;
                case CHILD_REMOVED:
                    _log.info("Removing DS: {}", e.getData().getPath());
                    removeDS(e.getData().getPath());
                    break;
            }
        });
        dsNodeCache.start();
    }
    /*
     * (non-Javadoc)
     *
     * @see org.dcache.nfsv4.NFSv41DeviceManager#layoutGet(CompoundContext context,
     *              Inode inode, int layoutType, int ioMode, stateid4 stateid)
     */
    @Override
    public Layout layoutGet(CompoundContext context, Inode inode, layouttype4 layoutType, int ioMode, stateid4 stateid)
            throws IOException {

        final NFS4Client client = context.getSession().getClient();
        final NFS4State nfsState = client.state(stateid);

        LayoutDriver layoutDriver = getLayoutDriver(layoutType);

        deviceid4[] deviceId;

        if (!context.getFs().hasIOLayout(inode)) {
            throw new LayoutUnavailableException("No dataservers available");
        } else {

            int mirrors = layoutType == layouttype4.LAYOUT4_FLEX_FILES ? 2 : 1;
            deviceId = _deviceMap.keySet().stream()
                    .unordered()
                    .limit(mirrors)
                    .toArray(deviceid4[]::new);

            if (deviceId.length == 0) {
                throw new LayoutUnavailableException("No dataservers available");
            }
        }

        NFS4State openState = nfsState.getOpenState();
        final stateid4 rawOpenState = openState.stateid();

        NFS4State layoutStateId = _openToLayoutStateid.get(rawOpenState);
        if(layoutStateId == null) {
            layoutStateId = client.createState(openState.getStateOwner(), openState);
            _openToLayoutStateid.put(stateid, layoutStateId);

            mdsStateIdCache.put(rawOpenState.other, context.currentInode().toNfsHandle());
            nfsState.addDisposeListener(
                    state -> {
                        _openToLayoutStateid.remove(rawOpenState);
                        mdsStateIdCache.remove(rawOpenState.other);
                    }
            );
        } else {
            layoutStateId.bumpSeqid();
        }

        nfs_fh4 fh = new nfs_fh4(context.currentInode().toNfsHandle());

        //  -1 is special value, which means entire file
        layout4 layout = new layout4();
        layout.lo_iomode = ioMode;
        layout.lo_offset = new offset4(0);
        layout.lo_length = new length4(nfs4_prot.NFS4_UINT64_MAX);
        layout.lo_content = layoutDriver.getLayoutContent(stateid,  NFSv4Defaults.NFS4_STRIPE_SIZE, fh, deviceId);

        return  new Layout(true, layoutStateId.stateid(), new layout4[]{layout});
    }

    /*
     * (non-Javadoc)
     *
     * @see org.dcache.nfsv4.NFSv41DeviceManager#getDeviceInfo(CompoundContext context, deviceid4 deviceId)
     */
    @Override
    public device_addr4 getDeviceInfo(CompoundContext context, deviceid4 deviceId, layouttype4 layoutType) throws ChimeraNFSException {

        _log.debug("lookup for device: {}, type: {}", deviceId, layoutType);

        InetSocketAddress[] addrs = _deviceMap.get(deviceId);
        if (addrs == null) {
            throw new NoEntException("Unknown device id: " + deviceId);
        }

        // limit addresses returned to client to the same 'type' as clients own address
        InetAddress clientAddress = context.getRemoteSocketAddress().getAddress();
        InetSocketAddress[] effectiveAddresses = Stream.of(addrs)
                .filter(a -> !a.getAddress().isLoopbackAddress() || clientAddress.isLoopbackAddress())
                .filter(a -> !a.getAddress().isLinkLocalAddress() || clientAddress.isLinkLocalAddress())
                .filter(a -> !a.getAddress().isSiteLocalAddress() || clientAddress.isSiteLocalAddress())
                .toArray(size -> new InetSocketAddress[size]);

        LayoutDriver layoutDriver = getLayoutDriver(layoutType);
        return layoutDriver.getDeviceAddress(effectiveAddresses);
    }

    /*
     * (non-Javadoc)
     *
     * @see org.dcache.nfsv4.NFSv41DeviceManager#getDeviceList()
     */
    @Override
    public List<deviceid4> getDeviceList(CompoundContext context) {
        return new ArrayList<>(_deviceMap.keySet());
    }

    /*
     * (non-Javadoc)
     *
     * @see org.dcache.nfsv4.NFSv41DeviceManager#layoutReturn()
     */
    @Override
    public void layoutReturn(CompoundContext context, stateid4 stateid, layouttype4 layoutType, byte[] body) throws ChimeraNFSException {
        _log.debug("release device for stateid {}", stateid);
        final NFS4Client client = context.getSession().getClient();
        final NFS4State layoutState = client.state(stateid);
        _openToLayoutStateid.remove(layoutState.getOpenState().stateid());
        getLayoutDriver(layoutType).acceptLayoutReturnData(body);
    }

    private LayoutDriver getLayoutDriver(layouttype4 layoutType) throws UnknownLayoutTypeException {
        LayoutDriver layoutDriver = _supportedDrivers.get(layoutType);
        if (layoutDriver == null) {
            throw new UnknownLayoutTypeException("Unsupported Layout type: " + layoutType);
        }
        return layoutDriver;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.dcache.nfsv4.NFSv41DeviceManager#getLayoutTypes()
     */
    @Override
    public Set<layouttype4> getLayoutTypes() {
        return _supportedDrivers.keySet();
    }

    private static deviceid4 deviceidOf(int id) {
        byte[] deviceidBytes = new byte[nfs4_prot.NFS4_DEVICEID4_SIZE];
        Bytes.putInt(deviceidBytes, 0, id);

        return new deviceid4(deviceidBytes);
    }

    private void addDS(String node) throws Exception {
        String id = node.substring(Paths.ZK_PATH_NODE.length() + Paths.ZK_PATH.length() + 1);
        int deviceId = Integer.parseInt(id);
        byte[] data = zkCurator.getData().forPath(node);
        InetSocketAddress[] address = ZkDataServer.stringToString(data);
        _deviceMap.put(deviceidOf(deviceId), address);
    }

    private void removeDS(String node) throws Exception {
        String id = node.substring(Paths.ZK_PATH_NODE.length() + Paths.ZK_PATH.length() + 1);
        int deviceId = Integer.parseInt(id);
        _deviceMap.remove(deviceidOf(deviceId));
    }
}
