# SQLServer Backup and Restore Changelog

## [Unreleased]
- Added support for IntelliJ 2024.3

## [1.0.6]
- Add support for differential backups (pick both files when restoring)

## [1.0.5]
- Idea 2024.1 compatible

## [1.0.4]
- `Backup & Download` feature is now optional
- File dialogs now show that the files might not be local
- Fix usage of deprecated API
- Increase since-version

## [1.0.3]
- Fix ArrayIndexOutOfBoundsException for Linux/macOS when restoring

## [1.0.2]
- Started using the IntelliJ Plugin Template
- Plugin is now signed

## [1.0.1]
### Fixed
- Added support for IntelliJ 2022.2

## [1.0.0]
### Added
- Plugin can be used while IntelliJ is indexing

### Fixed
- Downloading the backup doesn't trigger the unsafe query error anymore

## [0.8.1]
### Fixed
- SQLServer backup compression won't be done for Express, 'Express with Advanced Services' and Web editions even if it's turned on because they don't support it

## [0.8.0]
### Added
- gzipped files can be restored without needing to manually unzip them (Pull request from felhag)
- When downloading, the question to compress is asked before loading the backup into the database. This helps with backups that are bigger than 2gb which is the default maximum size for blobs (Pull request from felhag)
- Added setting to do compressed backups. The previous compression options are still available but shouldn't add much anymore
- Allow changing filenames when restoring a backup

### Fixed
- Listing files and drives is done similarly to what SSMS seems to do which means you shouldn't need sysadmin rights anymore
- Added some fixes to make backing up and restoring work for SQLServer running in a docker container

## [0.7.0]
### Changed
- The suggested name for downloading a backup is the name the backup was given when backing up (Pull request from felhag)
- Asking for compression and using database name as default filename is configurable

### Fixed
- When cancelling a backup & download the connection with the database will be closed

## [0.6.1]
### Fixed
- Fix compatibility with v201.7223.18.
- When the user can't list drives (EXEC master..xp_fixeddrives) a message is shown instead of a local file browser.

## [0.6.0]
### Added
- Progress indication when downloading backup
- Allow closing connections when restoring

## [0.5.1]
### Fixed
- Fix NPE when opening a file dialog

## [0.5.0]
### Changed
- Show error when restore action fails
- Progress for backup up and restoring is shown again
- Store the last selected path and (try to) use it when backing up and restoring later
- Fill in the backup filename when backing up and downloading

## [0.4.0]
### Fixed
- The filepicker asked for overwriting the file when a file was being selected for restoring. That doesn't happen anymore.

## [0.3.0]
### Fixed
- The filepicker for the backup action didn't always select the file, it sometimes picked the folder
- When overwriting an existing file in the backup action it didn't prompt to overwrite the file
- When a backup action fails a message is shown
- An internal API was used, it isn't anymore

## [0.2.0]
### Added
- Downloading using jtds or ms driver
- Refresh database after restore action

### Fixed
- No infinite wait anymore when reading data
- Sometimes the context menu items stayed disabled

## [0.1.0]
### Added
- Initial version. Seems to work fine locally :).