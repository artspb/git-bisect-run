<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# git-bisect-run Changelog

## [Unreleased]
### Added

## [0.8.9]
- 2025.3 compatibility.

## [0.8.8]
- 2025.2 compatibility.

## [0.8.7]
- 2025.1 compatibility.

## [0.8.6]
- 2024.3 compatibility.

## [0.8.5]
- 2024.2 compatibility.

## [0.8.4]
- 2024.1 compatibility.

## [0.8.3]
- 2023.3 compatibility.

## [0.8.2]
- Allow opening the UI from a range of commits.
- Display error output when available.

## [0.8.1]
- Disable the 'Bisect...' action in Git log if there's no log.

## [0.8.0]
- Rework the dialog.

## [0.7.8]
- 2031.2 compatibility.

## [0.7.7]
- 2031.1 compatibility.

## [0.7.6]
- 2022.3 compatibility.

## [0.7.5]
- 2022.2 compatibility.

## [0.7.4]
- 2022.1 compatibility.

## [0.7.3]
- 2021.3 compatibility.

## [0.7.2]
- Fix exception that occurs when working with test results.

## [0.7.1]
- 2021.2 compatibility.

## [0.7.0]
- 2020.3 compatibility.
- Add Bisect to the Run menu.
- Add Bisect to Git log (available on one or two selected commits).
- Add option to rely on test results instead of the exit code.
- Store previous bad/good revisions.
- Provide detailed information on error.
- Improve handling of a run configuration during indexing.
- Add good, bad, and skip actions to the Run menu.
- Add the Retry action to the error notification.
- Allow starting bisect from context.

## [0.6.0]
- 2021.1 compatibility.

## [0.5.3]
- 2020.3 compatibility.

## [0.5.2]
- 2020.2 compatibility.

## [0.5.1]
- 2020.1 EAP compatibility.

## [0.5.0]
- Add Show in Git Log action to the final notification.

## [0.4.4]
- Perform long operations under progress to prevent UI freezes.

## [0.4.3]
- Disable run icon for projects without Git repositories.
- 2020.1 compatibility.

## [0.4.2]
- 2019.3 compatibility.

## [0.4.1]
- Added shortcuts to configuration index (a.k.a. searchable options).
- 2019.2 compatibility.

## [0.4.0]
- Added ability to choose how the plugin should react in different situations e.g. zero/non-zero exit code 
  or broken compilation.

## [0.3.1]
- 2019.1 compatibility.

## [0.3.0]
- Fixed possible infinite bisect due to wrong checkout.

## [0.2.0]
- Implemented 'Synchronize by Revision' action which works on commits.

## [0.1.0]
- Implemented 'git bisect run' action which works on run configurations.
