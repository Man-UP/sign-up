package com.appspot.manup.signup.extrainfo;

import android.content.Intent;
import android.database.Cursor;
import android.util.Log;

import com.appspot.manup.signup.PersistentIntentService;
import com.appspot.manup.signup.Preferences;
import com.appspot.manup.signup.StateReporter;
import com.appspot.manup.signup.data.DataManager;
import com.appspot.manup.signup.data.DataManager.Member;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.novell.ldap.LDAPConnection;
import com.novell.ldap.LDAPException;
import com.novell.ldap.LDAPSearchResults;

public final class ExtraInfoService extends PersistentIntentService
{
    private static final String TAG = ExtraInfoService.class.getSimpleName();

    private static final String FORWARD_HOST = "localhost";
    private static final String LDAP_HOST = "edir.manchester.ac.uk";
    private static final int LDAP_PORT = 389;
    private static final int FORWARD_PORT = 23456;

    public static final int STATE_INITIALISING = 3;
    public static final int STATE_FAILED = 4;
    public static final int STATE_PORT_FORWARDING = 5;

    public static final Object ID = new Object();

    static
    {
        StateReporter.updateState(ID, STATE_STOPPED);
    }

    private final JSch mJsch = new JSch();
    private volatile DataManager mDataManager = null;
    private volatile Session mSession = null;

    public ExtraInfoService()
    {
        super(TAG);
    } // constructor()

    @Override
    public void onCreate()
    {
        super.onCreate();
        mDataManager = DataManager.getDataManager(this);
        setState(STATE_STARTED);
    } // onCreate()

    private boolean startPortForwarding()
    {
        Log.v(TAG, "Setting up port forwarding.");
        final Preferences prefs = new Preferences(this);
        if (mSession != null && mSession.isConnected())
        {
            return true;
        } // if
        setState(STATE_INITIALISING);
        try
        {
            /*
             * Throws JschException if user name or host not set, so no need for
             * us to check.
             */
            mSession = mJsch.getSession(prefs.getCsUsername(), prefs.getCsHost());
            mSession.setConfig("StrictHostKeyChecking", "no");
            mSession.setPassword(prefs.getCsPassword());
            mSession.connect();
            mSession.setPortForwardingL(FORWARD_PORT, LDAP_HOST, LDAP_PORT);
        } // try
        catch (final JSchException e)
        {
            if (mSession != null)
            {
                mSession.disconnect();
            } // if
            Log.e(TAG, "Failed to start port forwarding.");
            setState(STATE_FAILED);
            return false;
        } // catch
        Log.v(TAG, "Port forwaridng started.");
        setState(STATE_PORT_FORWARDING);
        return true;
    } // startPortForwarding()

    @Override
    protected void onHandleIntent(final Intent intent)
    {
        if (!startPortForwarding())
        {
            return;
        }
        Cursor cursor = null;
        try
        {
            cursor = mDataManager.queryMembers(
                    null /* all columns */,
                    Member.EXTRA_INFO_STATE + "!=? AND " + Member.PERSON_ID_VALIDATED + "!=?",
                    new String[] {
                            Member.EXTRA_INFO_STATE_RETRIEVED,
                            Member.PERSON_ID_VALIDATED_INVALID },
                    null /* order by */);
            if (cursor == null)
            {
                Log.e(TAG, "Failed to get members without extra info.");
                return;
            } // if
            if (!cursor.moveToFirst())
            {
                Log.v(TAG, "No members without extra info.");
                return;
            } // if
            final int idColumn = cursor.getColumnIndexOrThrow(Member._ID);
            final int personIdColumn = cursor.getColumnIndexOrThrow(Member.PERSON_ID);
            do
            {
                try
                {
                    queryLdapServer(cursor.getLong(idColumn), cursor.getString(personIdColumn));
                } // try
                catch (final LDAPException e)
                {
                    Log.w(TAG, "Failed to query LDAP server", e);
                } // catch
            } while (cursor.moveToNext() && !Thread.interrupted());
        } // try
        finally
        {
            if (cursor != null)
            {
                cursor.close();
            } // if
        } // finally
    } // retrieveExtraInfo()

    private void queryLdapServer(final long id, final String personId) throws LDAPException
    {
        if (!mDataManager.setExtraInfoStateToRetrieving(id))
        {
            Log.w(TAG, "Failed to set extra info state to retrieving for " + id + ". Ignoring.");
        } // if

        LDAPConnection conn = null;
        try
        {
            conn = new LDAPConnection();
            conn.connect(FORWARD_HOST, FORWARD_PORT);
            Log.v(TAG, "Looking up " + personId);
            final LDAPSearchResults results = conn.search(
                    "" /* base */,
                    LDAPConnection.SCOPE_SUB,
                    "umanPersonID=" + personId,
                    null /* attrs */,
                    false /* types only */);

            final MemberInfo memberEntry = extractLdapEntry(id, personId, results);

            if (memberEntry == null || !mDataManager.addExtraInfo(memberEntry))
            {
                Log.e(TAG, "Failed to add extra infomation for person ID " + personId);
            } // if
        } // try
        catch (final LDAPException e)
        {
            Log.d(TAG, "Setting error.");
            mDataManager.setExtraInfoStateToError(id);
            throw e;
        } // catch
        finally
        {
            if (conn != null)
            {
                conn.disconnect();
            } // if
        } // finally
    } // queryLdapServer

    private MemberInfo extractLdapEntry(final long id, final String personId,
            final LDAPSearchResults results)
    {
        MemberInfo memberEntry = null;

        /*
         * Don't use results.getCount(). It doesn't appear to work and always
         * returns 0.
         */

        if (results.hasMore())
        {
            try
            {
                memberEntry = MemberInfo.fromLdapEntry(results.next());
            } // try
            catch (final LDAPException e)
            {
                Log.w(TAG, "Failed to get LDAP search result.", e);
                return null;
            } // catch
        } // if

        if (memberEntry == null || results.hasMore())
        {
            Log.w(TAG, "No or multiple results, invalidating person ID " + personId);
            mDataManager.setPersonIdInvalid(id);
            mDataManager.setExtraInfoStateToError(id);
            return null;
        } // if

        Log.v(TAG, memberEntry.toString());
        return memberEntry;
    } // extactLdapEntry(long, String, LDAPSearchResults)

    @Override
    public void onDestroy()
    {
        if (mSession != null)
        {
            mSession.disconnect();
        } // if
        super.onDestroy();
        setState(STATE_STOPPED);
    } // onDestroy()

    @Override
    public Object getId()
    {
        return ID;
    } // getId()

} // class ExtraInfoService
