// kelondroCache.java
// (C) 2006 by Michael Peter Christen; mc@anomic.de, Frankfurt a. M., Germany
// first published 26.10.2006 on http://www.anomic.de
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2006-04-02 22:40:07 +0200 (So, 02 Apr 2006) $
// $LastChangedRevision: 1986 $
// $LastChangedBy: orbiter $
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA


package de.anomic.kelondro;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.anomic.kelondro.kelondroRow.Entry;
import de.anomic.server.serverMemory;

public class kelondroCache implements kelondroIndex {

    // this is a combined read cache and write buffer
    // we maintain four tables:
    // - a read-cache
    // - a miss-cache
    // - a write buffer for rows that are not contained in the target index
    // - a write buffer for rows that are known to be contained in target
    // furthermore, if we access a kelondroFlexTable, we can use the ram index of the underlying index

    // static object tracker; stores information about object cache usage
    private static final TreeMap<String, kelondroCache> objectTracker = new TreeMap<String, kelondroCache>();
    private static long memStopGrow    = 10000000; // a limit for the node cache to stop growing if less than this memory amount is available
    private static long memStartShrink =  6000000; // a limit for the node cache to start with shrinking if less than this memory amount is available
    
    // class objects
    private kelondroRowSet readHitCache;
    private kelondroRowSet readMissCache;
    private kelondroIndex  index;
    private kelondroRow    keyrow;
    private int            readHit, readMiss, writeUnique, writeDouble, cacheDelete, cacheFlush;
    private int            hasnotHit, hasnotMiss, hasnotUnique, hasnotDouble, hasnotDelete;
    
    public kelondroCache(kelondroIndex backupIndex) {
        this.index = backupIndex;
        init();
        objectTracker.put(backupIndex.filename(), this);
    }
    
    private void init() {
        this.keyrow = new kelondroRow(new kelondroColumn[]{index.row().column(index.row().primaryKeyIndex)}, index.row().objectOrder, 0);
        this.readHitCache = new kelondroRowSet(index.row(), 0);
        this.readMissCache = new kelondroRowSet(this.keyrow, 0);
        this.readHit = 0;
        this.readMiss = 0;
        this.writeUnique = 0;
        this.writeDouble = 0;
        this.cacheDelete = 0;
        this.cacheFlush = 0;
        this.hasnotHit = 0;
        this.hasnotMiss = 0;
        this.hasnotUnique = 0;
        this.hasnotDouble = 0;
        this.hasnotDelete = 0;
    }
    
    public final int cacheObjectChunkSize() {
        return index.row().objectsize;
    }
    
    public int writeBufferSize() {
        return 0;
    }
    
    public kelondroProfile profile() {
        return index.profile(); // TODO: implement own profile and merge with global
    }

    public static void setCacheGrowStati(long memStopGrowNew, long memStartShrinkNew) {
        memStopGrow = memStopGrowNew;
        memStartShrink =  memStartShrinkNew;
    }
    
    public static long getMemStopGrow() {
        return memStopGrow ;
    }
    
    public static long getMemStartShrink() {
        return memStartShrink ;
    }
    
    public static final Iterator<String> filenames() {
        // iterates string objects; all file names from record tracker
        return objectTracker.keySet().iterator();
    }

    public static final Map<String, String> memoryStats(String filename) {
        // returns a map for each file in the tracker;
        // the map represents properties for each record oobjects,
        // i.e. for cache memory allocation
        kelondroCache theObjectsCache = (kelondroCache) objectTracker.get(filename);
        return theObjectsCache.memoryStats();
    }
    
    private final Map<String, String> memoryStats() {
        // returns statistical data about this object
        HashMap<String, String> map = new HashMap<String, String>();
        map.put("objectHitChunkSize", (readHitCache == null) ? "0" : Integer.toString(readHitCache.rowdef.objectsize));
        map.put("objectHitCacheCount", (readHitCache == null) ? "0" : Integer.toString(readHitCache.size()));
        map.put("objectHitMem", (readHitCache == null) ? "0" : Integer.toString((int) (readHitCache.rowdef.objectsize * readHitCache.size() * kelondroRowCollection.growfactor)));
        map.put("objectHitCacheReadHit", Integer.toString(readHit));
        map.put("objectHitCacheReadMiss", Integer.toString(readMiss));
        map.put("objectHitCacheWriteUnique", Integer.toString(writeUnique));
        map.put("objectHitCacheWriteDouble", Integer.toString(writeDouble));
        map.put("objectHitCacheDeletes", Integer.toString(cacheDelete));
        map.put("objectHitCacheFlushes", Integer.toString(cacheFlush));
        
        map.put("objectMissChunkSize", (readMissCache == null) ? "0" : Integer.toString(readMissCache.rowdef.objectsize));
        map.put("objectMissCacheCount", (readMissCache == null) ? "0" : Integer.toString(readMissCache.size()));
        map.put("objectMissMem", (readMissCache == null) ? "0" : Integer.toString((int) (readMissCache.rowdef.objectsize * readMissCache.size() * kelondroRowCollection.growfactor)));
        map.put("objectMissCacheReadHit", Integer.toString(hasnotHit));
        map.put("objectMissCacheReadMiss", Integer.toString(hasnotMiss));
        map.put("objectMissCacheWriteUnique", Integer.toString(hasnotUnique));
        map.put("objectMissCacheWriteDouble", Integer.toString(hasnotDouble));
        map.put("objectMissCacheDeletes", Integer.toString(hasnotDelete));
        map.put("objectMissCacheFlushes", "0"); // a miss cache flush can only happen if we have a deletion cache (which we dont have)
        
        // future feature .. map.put("objectElderTimeRead", index.profile().)
        return map;
    }
    
    private int cacheGrowStatus() {
        return kelondroCachedRecords.cacheGrowStatus(serverMemory.available(), memStopGrow, memStartShrink);
    }
    
    private boolean checkMissSpace() {
        // returns true if it is allowed to write into this cache
        if (cacheGrowStatus() < 1) {
            if (readMissCache != null) {
                readMissCache.clear();
            }
            return false;
        }
        return true;
    }
    
    private boolean checkHitSpace() throws IOException {
        // returns true if it is allowed to write into this cache
        int status = cacheGrowStatus();
        if (status < 1) {
            if (readHitCache != null) {
                readHitCache.clear();
            }
            return false;
        }
        if (status < 2) {
            if (readHitCache != null) readHitCache.clear();
        }
        return true;
    }
    
    public synchronized void close() {
        index.close();
        readHitCache = null;
        readMissCache = null;
    }

    public boolean has(byte[] key) throws IOException {
        return (get(key) != null);
    }
    
    public synchronized Entry get(byte[] key) throws IOException {
        // first look into the miss cache
        if (readMissCache != null) {
            if (readMissCache.get(key) != null) {
                this.hasnotHit++;
                return null;
            } else {
                this.hasnotMiss++;
            }
        }

        Entry entry = null;
        
        // then try the hit cache and the buffers
        if (readHitCache != null) {
            entry = readHitCache.get(key);
            if (entry != null) {
                this.readHit++;
                return entry;
            }
        }
        
        // finally ask the backend index
        this.readMiss++;
        entry = index.get(key);
        // learn from result
        if (entry == null) {
            if ((checkMissSpace()) && (readMissCache != null)) {
                kelondroRow.Entry dummy = readMissCache.put(readMissCache.row().newEntry(key));
                if (dummy == null) this.hasnotUnique++; else this.hasnotDouble++;
            }
            return null;
        } else {
            if ((checkHitSpace()) && (readHitCache != null)) {
                kelondroRow.Entry dummy = readHitCache.put(entry);
                if (dummy == null) this.writeUnique++; else this.writeDouble++;
            }
            return entry;
        }
    }

    public synchronized void putMultiple(List<Entry> rows) throws IOException {
        Iterator<Entry> i = rows.iterator();
        while (i.hasNext()) put(i.next());
    }
    
    public synchronized void putMultiple(List<Entry> rows, Date entryDate) throws IOException {
        Iterator<Entry> i = rows.iterator();
        while (i.hasNext()) put(i.next(), entryDate);
    }
    
    public synchronized Entry put(Entry row) throws IOException {
        assert (row != null);
        assert (row.columns() == row().columns());
        //assert (!(serverLog.allZero(row.getColBytes(index.primarykey()))));
        
        byte[] key = row.getPrimaryKeyBytes();
        checkHitSpace();
        
        // remove entry from miss- and hit-cache
        if (readMissCache != null) {
            if (readMissCache.remove(key, true) != null) {
                this.hasnotHit++;
                // the entry does not exist before
                index.put(row); // write to backend
                if (readHitCache != null) {
                    kelondroRow.Entry dummy = readHitCache.put(row); // learn that entry
                    if (dummy == null) this.writeUnique++; else this.writeDouble++;
                }
                return null;
            }
        }
        
        Entry entry;
        
        if (readHitCache != null) {
            entry = readHitCache.get(key);
            if (entry != null) {
                // since we know that the entry was in the read cache, it cannot be in any write cache
                    // write directly to backend index
                    index.put(row);
                    // learn from situation
                    kelondroRow.Entry dummy = readHitCache.put(row); // overwrite old entry
                    if (dummy == null) this.writeUnique++; else this.writeDouble++;
                    return entry;
            }
        }

        // the worst case: we must write to the back-end directly
        entry = index.put(row);
        if (readHitCache != null) {
            kelondroRow.Entry dummy = readHitCache.put(row); // learn that entry
            if (dummy == null) this.writeUnique++; else this.writeDouble++;
        }
        return entry;
    }

    public synchronized Entry put(Entry row, Date entryDate) throws IOException {
        // a put with a date is bad for the cache: the date cannot be handled
        // The write buffer does not work here, because it does not store dates.
        
        throw new UnsupportedOperationException("put with date is inefficient in kelondroCache");
    }

    public synchronized void addUnique(Entry row) throws IOException {
        assert (row != null);
        assert (row.columns() == row().columns());
        //assert (!(serverLog.allZero(row.getColBytes(index.primarykey()))));
        
        byte[] key = row.getPrimaryKeyBytes();
        checkHitSpace();
        
        // remove entry from miss- and hit-cache
        if (readMissCache != null) {
            this.readMissCache.remove(key, true);
            this.hasnotDelete++;
            // the entry does not exist before
            index.addUnique(row); // write to backend
            if (readHitCache != null) {
                kelondroRow.Entry dummy = readHitCache.put(row); // learn that entry
                if (dummy == null) this.writeUnique++; else this.writeDouble++;
            }
            return;
        }
        
        // the worst case: we must write to the back-end directly
        index.addUnique(row);
        if (readHitCache != null) {
            kelondroRow.Entry dummy = readHitCache.put(row); // learn that entry
            if (dummy == null) this.writeUnique++; else this.writeDouble++;
        }
    }

    public synchronized void addUnique(Entry row, Date entryDate) throws IOException {
        if (entryDate == null) {
            addUnique(row);
            return;
        }
        
        assert (row != null);
        assert (row.columns() == row().columns());
        
        byte[] key = row.getPrimaryKeyBytes();
        checkHitSpace();
        
        // remove entry from miss- and hit-cache
        if (readMissCache != null) {
            this.readMissCache.remove(key, true);
            this.hasnotDelete++;
        }

        // the worst case: we must write to the backend directly
        index.addUnique(row);
        if (readHitCache != null) {
            kelondroRow.Entry dummy = readHitCache.put(row); // learn that entry
            if (dummy == null) this.writeUnique++; else this.writeDouble++;
        }
    }
    
    public synchronized void addUniqueMultiple(List<Entry> rows) throws IOException {
        Iterator<Entry> i = rows.iterator();
        while (i.hasNext()) addUnique((Entry) i.next());
    }

    public synchronized Entry remove(byte[] key, boolean keepOrder) throws IOException {
        checkMissSpace();
        
        // add entry to miss-cache
        if (readMissCache != null) {
            // set the miss cache; if there was already an entry we know that the return value must be null
            kelondroRow.Entry dummy = readMissCache.put(readMissCache.row().newEntry(key));
            if (dummy == null) {
                this.hasnotUnique++;
            } else {
                this.hasnotHit++;
                this.hasnotDouble++;
            }
        }
        
        // remove entry from hit-cache
        if (readHitCache != null) {
            Entry entry = readHitCache.remove(key, true);
            if (entry == null) {
                this.readMiss++;
            } else {
                this.readHit++;
                this.cacheDelete++;
            }
        }
        
        return index.remove(key, false);
    }

    public synchronized Entry removeOne() throws IOException {
        
        checkMissSpace();
        
        Entry entry = index.removeOne();
        if (entry == null) return null;
        byte[] key = entry.getPrimaryKeyBytes();
        if (readMissCache != null) {
            kelondroRow.Entry dummy = readMissCache.put(readMissCache.row().newEntry(key));
            if (dummy == null) this.hasnotUnique++; else this.hasnotDouble++;
        }
        if (readHitCache != null) {
            kelondroRow.Entry dummy = readHitCache.remove(key, true);
            if (dummy != null) this.cacheDelete++;
        }
        return entry;
    }

    public synchronized kelondroRow row() {
        return index.row();
    }

    public synchronized kelondroCloneableIterator<byte[]> keys(boolean up, byte[] firstKey) throws IOException {
        return index.keys(up, firstKey);
    }

    public synchronized kelondroCloneableIterator<kelondroRow.Entry> rows(boolean up, byte[] firstKey) throws IOException {
        return index.rows(up, firstKey);
    }

    public int size() {
        return index.size();
    }

    public String filename() {
        return index.filename();
    }

	public void reset() throws IOException {
		this.index.reset();
		init();
	}

}
