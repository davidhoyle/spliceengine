package com.splicemachine.hbase;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.splicemachine.constants.SpliceConstants;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.client.MetaScanner;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Writables;
import org.apache.log4j.Logger;

import javax.management.*;
import java.io.IOException;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.*;

/**
 * @author Scott Fines
 *         Created on: 8/8/13
 */
public class HBaseRegionCache implements RegionCache {
    private static final Logger CACHE_LOG = Logger.getLogger(HBaseRegionCache.class);
    private final LoadingCache<Integer,SortedSet<HRegionInfo>> regionCache;
    private final ScheduledExecutorService cacheUpdater;

    private final long cacheUpdatePeriod;
    private volatile long cacheUpdatedTimestamp;
    private RegionCacheLoader regionCacheLoader;

    private RegionCacheStatus status = new RegionStatus();

    private HBaseRegionCache(long cacheUpdatePeriod,
                             LoadingCache<Integer, SortedSet<HRegionInfo>> regionCache,
                             ScheduledExecutorService cacheUpdater) {
        this.regionCache = regionCache;
        this.cacheUpdater = cacheUpdater;
        this.cacheUpdatePeriod = cacheUpdatePeriod;
    }

    public static RegionCache create(long cacheExpirationPeriod,long cacheUpdatePeriod){
        LoadingCache<Integer,SortedSet<HRegionInfo>> regionCache =
                CacheBuilder.newBuilder()
                        .expireAfterWrite(cacheExpirationPeriod, TimeUnit.SECONDS)
                        .build(new RegionLoader(SpliceConstants.config));

        ThreadFactory cacheFactory = new ThreadFactoryBuilder()
                .setNameFormat("tablewriter-cacheupdater-%d")
                .setDaemon(true)
                .setPriority(Thread.NORM_PRIORITY).build();
        ScheduledExecutorService cacheUpdater = Executors.newSingleThreadScheduledExecutor(cacheFactory);

        return new HBaseRegionCache(cacheUpdatePeriod,regionCache,cacheUpdater);
    }

    @Override
    public void start(){
        regionCacheLoader = new RegionCacheLoader();
        cacheUpdater.scheduleAtFixedRate(regionCacheLoader,0l,cacheUpdatePeriod, TimeUnit.MILLISECONDS);
    }

    @Override
    public void shutdown(){
        cacheUpdater.shutdownNow();
    }

    @Override
    public SortedSet<HRegionInfo> getRegions(byte[] tableName) throws ExecutionException {
        return regionCache.get(Bytes.mapKey(tableName));
    }

    @Override
    public void invalidate(byte[] tableName){
        regionCache.invalidate(Bytes.mapKey(tableName));
    }

    @Override
    public long size(){
        return regionCache.size();
    }

    @Override
    public long getUpdateTimestamp() {
        return cacheUpdatedTimestamp;
    }

    @Override
    public void registerJMX(MBeanServer mbs) throws MalformedObjectNameException, NotCompliantMBeanException, InstanceAlreadyExistsException, MBeanRegistrationException {
        ObjectName cacheName = new ObjectName("com.splicemachine.region:type=RegionCacheStatus");
        mbs.registerMBean(status,cacheName);
    }

    private class RegionCacheLoader  implements Runnable{

        @Override
        public void run() {
            SpliceLogUtils.debug(CACHE_LOG, "Refreshing Region cache for all tables");
            final Map<byte[],SortedSet<HRegionInfo>> regionInfos = Maps.newHashMap();
            MetaScanner.MetaScannerVisitor visitor = new MetaScanner.MetaScannerVisitor() {
                @Override
                public boolean processRow(Result rowResult) throws IOException {
                    byte[] bytes = rowResult.getValue(HConstants.CATALOG_FAMILY,HConstants.REGIONINFO_QUALIFIER);
                    if(bytes==null){
                        //TODO -sf- log a message here
                        return true;
                    }
                    HRegionInfo info = Writables.getHRegionInfo(bytes);
                    SortedSet<HRegionInfo> regions = regionInfos.get(info.getTableName());
                    if(regions==null){
                        regions = new ConcurrentSkipListSet<HRegionInfo>();
                        regionInfos.put(info.getTableName(),regions);
                    }
                    if(!(info.isOffline()||info.isSplit()))
                        regions.add(info);
                    return true;
                }

                @Override
                public void close() throws IOException {
                    // not used ... -- JL
                }
            };

            try {
                MetaScanner.metaScan(SpliceConstants.config,visitor);
            } catch (IOException e) {
                SpliceLogUtils.error(CACHE_LOG,"Unable to update region cache",e);
            }
            for(byte[] table:regionInfos.keySet()){
                SpliceLogUtils.trace(CACHE_LOG,"Updating cache for "+ Bytes.toString(table));
                regionCache.put(Bytes.mapKey(table),regionInfos.get(table));
            }
            cacheUpdatedTimestamp = System.currentTimeMillis();
        }
    }

    private static class RegionLoader extends CacheLoader<Integer, SortedSet<HRegionInfo>> {
        private final Configuration configuration;

        public RegionLoader(Configuration configuration) {
            this.configuration = configuration;
        }

        @Override
        public SortedSet<HRegionInfo> load(final Integer key) throws Exception {
            SpliceLogUtils.trace(CACHE_LOG,"Loading regions for key %d",key);
            final SortedSet<HRegionInfo> regionInfos = new ConcurrentSkipListSet<HRegionInfo>();
            final MetaScanner.MetaScannerVisitor visitor = new MetaScanner.MetaScannerVisitor() {
                @Override
                public boolean processRow(Result rowResult) throws IOException {
                    byte[] bytes = rowResult.getValue(HConstants.CATALOG_FAMILY,HConstants.REGIONINFO_QUALIFIER);
                    if(bytes==null){
                        //TODO -sf- log a message here
                        return true;
                    }
                    HRegionInfo info = Writables.getHRegionInfo(bytes);
                    Integer tableKey = Bytes.mapKey(info.getTableName());
                    if(key.equals(tableKey)&& !(info.isOffline()||info.isSplit())){
                        regionInfos.add(info);
                    }
                    return true;
                }

                @Override
                public void close() throws IOException {
                    // We do not use this... -- JL
                }
            };

            try {
                MetaScanner.metaScan(configuration,visitor);
            } catch (IOException e) {
                SpliceLogUtils.error(CACHE_LOG,"Unable to update region cache",e);
            }
            SpliceLogUtils.trace(CACHE_LOG,"loaded regions %s",regionInfos);
            return regionInfos;
        }
    }

    private class RegionStatus implements RegionCacheStatus{

        @Override
        public long getLastUpdatedTimestamp() {
            return cacheUpdatedTimestamp;
        }

        @Override
        public void updateCache() {
            regionCacheLoader.run();
        }

        @Override
        public int getNumCachedRegions(String tableName) {
            try {
                return regionCache.get(Bytes.mapKey(tableName.getBytes())).size();
            } catch (ExecutionException e) {
                return -1;
            }
        }

        @Override
        public long getNumCachedTables() {
            return regionCache.size();
        }

        @Override
        public long getCacheUpdatePeriod() {
            return cacheUpdatePeriod;
        }
    }

}
