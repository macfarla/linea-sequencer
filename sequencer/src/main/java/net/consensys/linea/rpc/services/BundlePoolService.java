/*
 * Copyright Consensys Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package net.consensys.linea.rpc.services;

import static java.util.stream.Collectors.joining;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import lombok.Getter;
import lombok.experimental.Accessors;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.plugin.services.BesuService;

public interface BundlePoolService extends BesuService {

  /** TransactionBundle record representing a collection of pending transactions with metadata. */
  @Accessors(fluent = true)
  @Getter
  class TransactionBundle {
    private static final String FIELD_SEPARATOR = "|";
    private static final String ITEM_SEPARATOR = ",";
    private static final String LINE_TERMINATOR = "$";
    private final Hash bundleIdentifier;
    private final List<PendingBundleTx> pendingTransactions;
    private final Long blockNumber;
    private final Optional<Long> minTimestamp;
    private final Optional<Long> maxTimestamp;
    private final Optional<List<Hash>> revertingTxHashes;

    public TransactionBundle(
        final Hash bundleIdentifier,
        final List<Transaction> transactions,
        final Long blockNumber,
        final Optional<Long> minTimestamp,
        final Optional<Long> maxTimestamp,
        final Optional<List<Hash>> revertingTxHashes) {
      this.bundleIdentifier = bundleIdentifier;
      this.pendingTransactions = transactions.stream().map(PendingBundleTx::new).toList();
      this.blockNumber = blockNumber;
      this.minTimestamp = minTimestamp;
      this.maxTimestamp = maxTimestamp;
      this.revertingTxHashes = revertingTxHashes;
    }

    public String serializeForDisk() {
      // version=1 | blockNumber | bundleIdentifier | minTimestamp | maxTimestamp |
      // revertingTxHashes, | txs, $
      return new StringBuilder("1")
          .append(FIELD_SEPARATOR)
          .append(blockNumber)
          .append(FIELD_SEPARATOR)
          .append(bundleIdentifier.toHexString())
          .append(FIELD_SEPARATOR)
          .append(minTimestamp.map(l -> l + FIELD_SEPARATOR).orElse(FIELD_SEPARATOR))
          .append(maxTimestamp.map(l -> l + FIELD_SEPARATOR).orElse(FIELD_SEPARATOR))
          .append(
              revertingTxHashes
                  .map(l -> l.stream().map(Hash::toHexString).collect(joining(ITEM_SEPARATOR)))
                  .orElse(FIELD_SEPARATOR))
          .append(
              pendingTransactions.stream()
                  .map(PendingBundleTx::serializeForDisk)
                  .collect(joining(ITEM_SEPARATOR)))
          .append(LINE_TERMINATOR)
          .toString();
    }

    public static TransactionBundle restoreFromSerialized(final String str) {
      if (!str.endsWith(LINE_TERMINATOR)) {
        throw new IllegalArgumentException(
            "Unterminated bundle serialization, missing terminal " + LINE_TERMINATOR);
      }

      final var parts =
          str.substring(0, str.length() - LINE_TERMINATOR.length())
              .split(Pattern.quote(FIELD_SEPARATOR));
      if (!parts[0].equals("1")) {
        throw new IllegalArgumentException("Unsupported bundle serialization version " + parts[0]);
      }
      if (parts.length != 7) {
        throw new IllegalArgumentException(
            "Invalid bundle serialization, expected 7 fields but got " + parts.length);
      }

      final var blockNumber = Long.parseLong(parts[1]);
      final var bundleIdentifier = Hash.fromHexString(parts[2]);
      final Optional<Long> minTimestamp =
          parts[3].isEmpty() ? Optional.empty() : Optional.of(Long.parseLong(parts[3]));
      final Optional<Long> maxTimestamp =
          parts[4].isEmpty() ? Optional.empty() : Optional.of(Long.parseLong(parts[4]));
      final Optional<List<Hash>> revertingTxHashes =
          parts[5].isEmpty()
              ? Optional.empty()
              : Optional.of(
                  Arrays.stream(parts[5].split(Pattern.quote(ITEM_SEPARATOR)))
                      .map(Hash::fromHexString)
                      .toList());
      final var transactions =
          Arrays.stream(parts[6].split(Pattern.quote(ITEM_SEPARATOR)))
              .map(Bytes::fromBase64String)
              .map(Transaction::readFrom)
              .toList();

      return new TransactionBundle(
          bundleIdentifier,
          transactions,
          blockNumber,
          minTimestamp,
          maxTimestamp,
          revertingTxHashes);
    }

    /** A pending transaction contained in a bundle. */
    public class PendingBundleTx
        extends org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction.Local {

      public PendingBundleTx(final org.hyperledger.besu.ethereum.core.Transaction transaction) {
        super(transaction);
      }

      public TransactionBundle getBundle() {
        return TransactionBundle.this;
      }

      public boolean isBundleStart() {
        return getBundle().pendingTransactions().getFirst().equals(this);
      }

      @Override
      public String toTraceLog() {
        return "Bundle tx: " + super.toTraceLog();
      }

      String serializeForDisk() {
        final var rlpOutput = new BytesValueRLPOutput();
        getTransaction().writeTo(rlpOutput);
        return rlpOutput.encoded().toBase64String();
      }
    }
  }

  /**
   * Retrieves a list of TransactionBundles associated with a block number.
   *
   * @param blockNumber The block number to look up.
   * @return A list of TransactionBundles for the given block number, or an empty list if none are
   *     found.
   */
  List<TransactionBundle> getBundlesByBlockNumber(long blockNumber);

  /**
   * Retrieves a TransactionBundle by its unique hash identifier.
   *
   * @param hash The hash identifier of the TransactionBundle.
   * @return The TransactionBundle associated with the hash, or null if not found.
   */
  TransactionBundle get(Hash hash);

  /**
   * Retrieves a TransactionBundle by its replacement UUID
   *
   * @param replacementUUID identifier of the TransactionBundle.
   * @return The TransactionBundle associated with the uuid, or null if not found.
   */
  TransactionBundle get(UUID replacementUUID);

  /**
   * Puts or replaces an existing TransactionBundle in the cache and updates the block index.
   *
   * @param hash The hash identifier of the TransactionBundle.
   * @param bundle The new TransactionBundle to replace the existing one.
   */
  void putOrReplace(Hash hash, TransactionBundle bundle);

  /**
   * Puts or replaces an existing TransactionBundle by UUIDin the cache and updates the block index.
   *
   * @param replacementUUID identifier of the TransactionBundle.
   * @param bundle The new TransactionBundle to replace the existing one.
   */
  void putOrReplace(UUID replacementUUID, TransactionBundle bundle);

  /**
   * removes an existing TransactionBundle in the cache and updates the block index.
   *
   * @param replacementUUID identifier of the TransactionBundle.
   * @return boolean indicating if bundle was found and removed
   */
  boolean remove(UUID replacementUUID);

  /**
   * removes an existing TransactionBundle in the cache and updates the block index.
   *
   * @param hash The hash identifier of the TransactionBundle.
   * @return boolean indicating if bundle was found and removed
   */
  boolean remove(Hash hash);

  /**
   * Removes all TransactionBundles associated with the given block number. First removes them from
   * the block index, then removes them from the cache.
   *
   * @param blockNumber The block number whose bundles should be removed.
   */
  void removeByBlockNumber(long blockNumber);

  /**
   * Get the number of bundles in the pool
   *
   * @return the number of bundles in the pool
   */
  long size();

  void saveToDisk();

  void loadFromDisk();
}
