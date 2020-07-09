/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.apitest.linux;

import java.io.File;
import java.io.IOException;

import lombok.extern.slf4j.Slf4j;

import static bisq.apitest.linux.BashCommand.isAlive;
import static java.lang.String.format;



import bisq.apitest.config.ApiTestConfig;

@Slf4j
abstract class AbstractLinuxProcess implements LinuxProcess {

    protected final String name;

    protected long pid;

    protected final ApiTestConfig config;

    public AbstractLinuxProcess(String name, ApiTestConfig config) {
        this.name = name;
        this.config = config;
    }

    @Override
    public String getName() {
        return this.name;
    }

    public void verifyBitcoinConfig() {
        verifyBitcoinConfig(false);
    }

    public void verifyBitcoinConfig(boolean verbose) {
        if (verbose)
            log.info(format("Checking bitcoind env...%n"
                            + "\t%-20s%s%n\t%-20s%s%n\t%-20s%s%n\t%-20s%s%n\t%-20s%s",
                    "berkeleyDbLibPath", config.berkeleyDbLibPath,
                    "bitcoinPath", config.bitcoinPath,
                    "bitcoinDatadir", config.bitcoinDatadir,
                    "bitcoin.conf", config.bitcoinDatadir + "/bitcoin.conf",
                    "blocknotify", config.bitcoinDatadir + "/blocknotify"));

        File berkeleyDbLibPath = new File(config.berkeleyDbLibPath);
        if (!berkeleyDbLibPath.exists() || !berkeleyDbLibPath.canExecute())
            throw new IllegalStateException(berkeleyDbLibPath + "cannot be found or executed");

        File bitcoindExecutable = new File(config.bitcoinPath);
        if (!bitcoindExecutable.exists() || !bitcoindExecutable.canExecute())
            throw new IllegalStateException(bitcoindExecutable + "cannot be found or executed");

        File bitcoindDatadir = new File(config.bitcoinDatadir);
        if (!bitcoindDatadir.exists() || !bitcoindDatadir.canWrite())
            throw new IllegalStateException(bitcoindDatadir + "cannot be found or written to");

        File bitcoinConf = new File(bitcoindDatadir, "bitcoin.conf");
        if (!bitcoinConf.exists() || !bitcoinConf.canRead())
            throw new IllegalStateException(bitcoindDatadir + "cannot be found or read");

        File blocknotify = new File(bitcoindDatadir, "blocknotify");
        if (!blocknotify.exists() || !blocknotify.canExecute())
            throw new IllegalStateException(bitcoindDatadir + "cannot be found or executed");
    }

    public void verifyBitcoindRunning() throws IOException, InterruptedException {
        long bitcoindPid = BashCommand.getPid("bitcoind");
        if (bitcoindPid < 0 || !isAlive(bitcoindPid))
            throw new IllegalStateException("Bitcoind not running");
    }
}
