package com.kooo.evcam.repair;

import java.io.File;
import java.io.IOException;
import com.kooo.evcam.storage.VerifiedFileCopy;

/** The developer repair still edits its working file; retain verified original bytes first. */
final class RepairBackup {
    private RepairBackup() {}
    static File preserve(File source) throws IOException {
        File backup = new File(new File(source.getParentFile(), "repair-originals"), source.getName() + ".original");
        VerifiedFileCopy.copy(source, backup);
        return backup;
    }
}
