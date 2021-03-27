# intellij-sqlserver-backup-restore
[Plugin on Jetbrains website](https://plugins.jetbrains.com/plugin/13913)

A plugin that allows creating backups and restoring them from the DataGrip context for Microsoft SQLServer databases. <br>
<br>
The plugin built for my own personal use case which means that it will work for databases that are connected through an SSH tunnel.
It supports downloading backups from the remote server to the local machine without using `xp_cmdshell` command.<br>
<br>
Features:<br>
<ul>
  <li> Creating a backup and storing it on the server </li>
  <li> Creating a backup, storing it and download it right after </li>
  <li> Reading a backup into an existing database </li>
  <li> Reading a backup into a newly created database </li>
</ul>
Built for 2020.1 and higher because the internal database api has changed in that version.

**Note:** AWS and probably also Azure don't seem to support file based backing up and are not supported by this plugin.

## Change notes
0.8.1
- SQLServer backup compression won't be done for Express, 'Express with Advanced Services' and Web editions even if it's turned on because they don't support it 

0.8
- gzipped files can be restored without needing to manually unzip them (Pull request from felhag)
- When downloading, the question to compress is asked before loading the backup into the database. This helps with backups that are bigger than 2gb which is the default maximum size for blobs (Pull request from felhag)  
- Added setting to do compressed backups. The previous compression options are still available but shouldn't add much anymore
- Listing files and drives is done similarly to what SSMS seems to do which means you shouldn't need sysadmin rights anymore
- Added some fixes to make backing up and restoring work for SQLServer running in a docker container
- Allow changing filenames when restoring a backup

0.7
- The suggested name for downloading a backup is the name the backup was given when backing up (Pull request from felhag)
- When cancelling a backup & download the connection with the database will be closed
- Asking for compression and using database name as default filename is configurable

0.6.1
- Fix compatibility with v201.7223.18.
- When the user can't list drives (EXEC master..xp_fixeddrives) a message is shown instead of a local file browser.

0.6
- Progress indication when downloading backup
- Allow closing connections when restoring

0.5.1
- Fix NPE when opening a file dialog

0.5
- Show error when restore action fails
- Progress for backup up and restoring is shown again
- Store the last selected path and (try to) use it when backing up and restoring later
- Fill in the backup filename when backing up and downloading

0.4
- The filepicker asked for overwriting the file when a file was being selected for restoring. That doesn't happen anymore.

0.3
- The filepicker for the backup action didn't always select the file, it sometimes picked the folder
- When overwriting an existing file in the backup action it didn't prompt to overwrite the file
- When a backup action fails a message is shown
- An internal API was used, it isn't anymore

0.2
- Downloading using jtds or ms driver
- No infinite wait anymore when reading data
- Refresh database after restore action
- Sometimes the context menu items stayed disabled

0.1
- Initial version. Seems to work fine locally :).
