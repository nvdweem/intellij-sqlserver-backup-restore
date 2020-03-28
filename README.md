# intellij-sqlserver-backup-restore
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
</ul><br>
<br>
Currently only works with the 2020 EAP versions because the API for the database seems to have been changed.

## Change notes
0.5
- Show error when restore action fails

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
