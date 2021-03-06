package simpledb;

import java.io.*;
import java.util.*;

/**
 * BufferPool manages the reading and writing of pages into memory from
 * disk. Access methods call into it to retrieve pages, and it fetches
 * pages from the appropriate location.
 * <p>
 * The BufferPool is also responsible for locking;  when a transaction fetches
 * a page, BufferPool checks that the transaction has the appropriate
 * locks to read/write the page.
 */
public class BufferPool {
    /** Bytes per page, including header. */
    public static final int PAGE_SIZE = 4096;

    /** Default number of pages passed to the constructor. This is used by
    other classes. BufferPool should use the numPages argument to the
    constructor instead. */
    public static final int DEFAULT_PAGES = 50;
    private int max_page;
    private LinkedHashMap<PageId, Page> pages;
    private LockManager lockmanager;
    private PageId nowpid;
    /**
     * Creates a BufferPool that caches up to numPages pages.
     *
     * @param numPages maximum number of pages in this buffer pool.
     */
    public BufferPool(int numPages) {
        // some code goes here
        this.max_page = numPages;
        this.pages = new LinkedHashMap<PageId, Page>();
        lockmanager = new LockManager();
    }

    /**
     * Retrieve the specified page with the associated permissions.
     * Will acquire a lock and may block if that lock is held by another
     * transaction.
     * <p>
     * The retrieved page should be looked up in the buffer pool.  If it
     * is present, it should be returned.  If it is not present, it should
     * be added to the buffer pool and returned.  If there is insufficient
     * space in the buffer pool, an page should be evicted and the new page
     * should be added in its place.
     *
     * @param tid the ID of the transaction requesting the page
     * @param pid the ID of the requested page
     * @param perm the requested permissions on the page
     */
    public synchronized Page getPage(TransactionId tid, PageId pid, Permissions perm)
        throws TransactionAbortedException, DbException {
        // some code goes here
        boolean hasLock = lockmanager.getLock(perm, pid, tid);
        long stime = System.currentTimeMillis();
       
        while(!hasLock){
            if(System.currentTimeMillis() - stime > 300){
                throw new TransactionAbortedException();
            }
            try{
                Thread.sleep(10);
                hasLock = lockmanager.getLock(perm, pid, tid);
            }catch (Exception e){
                e.printStackTrace();
            }
        }
        Page mypage = pages.get(pid);
        if(mypage == null){
            DbFile file = Database.getCatalog().getDbFile(pid.getTableId());
            mypage = file.readPage(pid);
            if(pages.size() >= this.max_page){
                boolean flag = false;
                for(Page page : pages.values()){
                    if(page.isDirty() == null){
                        flag = true;
                        try {
                            flushPage(page.getId());
                            pages.remove(page.getId());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                    }
                }
                if(!flag) throw new DbException("All pages in BufferPool are dirty!");
            }
            pages.put(pid, mypage);
        }
        nowpid = pid;
        return mypage;
    }

    /**
     * Releases the lock on a page.
     * Calling this is very risky, and may result in wrong behavior. Think hard
     * about who needs to call this and why, and why they can run the risk of
     * calling it.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param pid the ID of the page to unlock
     */
    public synchronized void releasePage(TransactionId tid, PageId pid) {
        // some code goes here
        // not necessary for proj1
        lockmanager.releaseOneLock(pid, tid);
    }

    /**
     * Release all locks associated with a given transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     */
    public synchronized void transactionComplete(TransactionId tid) throws IOException {
        // some code goes here
        // not necessary for proj1
        transactionComplete(tid, true);
    }

    /** Return true if the specified transaction has a lock on the specified page */
    public boolean holdsLock(TransactionId tid, PageId p) {
        // some code goes here
        // not necessary for proj1
        return lockmanager.holdsLock(p, tid);
    }

    /**
     * Commit or abort a given transaction; release all locks associated to
     * the transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param commit a flag indicating whether we should commit or abort
     */
    public synchronized void transactionComplete(TransactionId tid, boolean commit)
        throws IOException {
        // some code goes here
        // not necessary for proj1
        if(commit){
            flushPages(tid);
        }else{
            for(Map.Entry<PageId, Page> entry : pages.entrySet()){
                Page tmppage = entry.getValue();
                PageId tmppid = entry.getKey();
                if(tmppage.isDirty() != null && tmppage.isDirty().equals(tid)){
                    tmppage = Database.getCatalog().getDbFile(tmppid.getTableId()).readPage(tmppid);
                    pages.put(tmppid, tmppage);
                }
            }
        }
        lockmanager.releaseAllLocks(tid);
//        lockmanager.debugPage(nowpid);
    }

    /**
     * Add a tuple to the specified table behalf of transaction tid.  Will
     * acquire a write lock on the page the tuple is added to(Lock 
     * acquisition is not needed for lab2). May block if the lock cannot 
     * be acquired.
     * 
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and updates cached versions of any pages that have 
     * been dirtied so that future requests see up-to-date pages. 
     *
     * @param tid the transaction adding the tuple
     * @param tableId the table to add the tuple to
     * @param t the tuple to add
     */
    public synchronized void insertTuple(TransactionId tid, int tableId, Tuple t)
        throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        // not necessary for proj1
        HeapFile file = (HeapFile)Database.getCatalog().getDbFile(tableId);
        ArrayList<Page> dirpages = file.insertTuple(tid, t);
        for(Page page : dirpages){
            page.markDirty(true, tid);
            this.pages.put(page.getId(), page);
        }
        return ;
    }

    /**
     * Remove the specified tuple from the buffer pool.
     * Will acquire a write lock on the page the tuple is removed from. May block if
     * the lock cannot be acquired.
     *
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit.  Does not need to update cached versions of any pages that have 
     * been dirtied, as it is not possible that a new page was created during the deletion
     * (note difference from addTuple).
     *
     * @param tid the transaction adding the tuple.
     * @param t the tuple to add
     */
    public synchronized void deleteTuple(TransactionId tid, Tuple t)
        throws DbException, TransactionAbortedException {
        // some code goes here
        // not necessary for proj1
        HeapFile file = (HeapFile)Database.getCatalog().getDbFile(t.getRecordId().getPageId().getTableId());
        Page page = file.deleteTuple(tid, t);
        if(pages.get(page.getId()) != null) pages.put(page.getId(), page);
        return ;
    }

    /**
     * Flush all dirty pages to disk.
     * NB: Be careful using this routine -- it writes dirty data to disk so will
     *     break simpledb if running in NO STEAL mode.
     */
    public synchronized void flushAllPages() throws IOException {
        // some code goes here
        // not necessary for proj1
        for(PageId pid : pages.keySet())
          flushPage(pid);
    }

    /** Remove the specific page id from the buffer pool.
        Needed by the recovery manager to ensure that the
        buffer pool doesn't keep a rolled back page in its
        cache.
    */
    public synchronized void discardPage(PageId pid) {
        // some code goes here
    // not necessary for proj1
    }

    /**
     * Flushes a certain page to disk
     * @param pid an ID indicating the page to flush
     */
    private synchronized  void flushPage(PageId pid) throws IOException {
        // some code goes here
        // not necessary for proj1
        HeapFile file = (HeapFile)Database.getCatalog().getDbFile(pid.getTableId());
        Page page = pages.get(pid);
        file.writePage(page);
        page.markDirty(false, null);
    }

    /** Write all pages of the specified transaction to disk.
     */
    public synchronized  void flushPages(TransactionId tid) throws IOException {
        // some code goes here
        // not necessary for proj1
        for(Map.Entry<PageId, Page> entry : pages.entrySet()){
            Page page = entry.getValue();
            if(page.isDirty() != null && page.isDirty().equals(tid))
              flushPage(page.getId());
        }
    }

    /**
     * Discards a page from the buffer pool.
     * Flushes the page to disk to ensure dirty pages are updated on disk.
     */
    private synchronized  void evictPage() throws DbException {
        // some code goes here
        // not necessary for proj1
    }

}