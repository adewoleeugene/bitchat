use solana_program::{
    account_info::{next_account_info, AccountInfo},
    entrypoint,
    entrypoint::ProgramResult,
    msg,
    program::invoke_signed,
    program_error::ProgramError,
    pubkey::Pubkey,
    rent::Rent,
    system_instruction, system_program,
    sysvar::Sysvar,
};

pub const LENDING_CHANNEL_SEED: &[u8] = b"lending-channel";
pub const LOAN_REQUEST_SEED: &[u8] = b"loan-request";
pub const VOTE_RECORD_SEED: &[u8] = b"vote-record";
pub const PURPOSE_HASH_LEN: usize = 32;
pub const PUBKEY_LEN: usize = 32;
pub const MAX_ID_LEN: usize = 64;

pub const STATUS_PENDING: u8 = 0;
pub const STATUS_APPROVED: u8 = 1;
pub const STATUS_REJECTED: u8 = 2;
pub const STATUS_DISBURSED: u8 = 3;
pub const STATUS_REPAID: u8 = 4;
pub const STATUS_DEFAULTED: u8 = 5;
pub const STATUS_CANCELLED: u8 = 6;

pub const CHANNEL_ACTIVE: u8 = 0;
pub const CHANNEL_PAUSED: u8 = 1;
pub const CHANNEL_CLOSED: u8 = 2;

pub const BORROWER_INDIVIDUAL: u8 = 0;
pub const BORROWER_GROUP: u8 = 1;

pub const VOTE_YES: u8 = 1;
pub const VOTE_NO: u8 = 2;

pub const LENDING_CHANNEL_ACCOUNT_LEN: usize = 101;
pub const LOAN_REQUEST_ACCOUNT_LEN: usize = 164;
pub const VOTE_RECORD_ACCOUNT_LEN: usize = 76;

solana_program::declare_id!("11111111111111111111111111111111");

#[cfg(not(feature = "no-entrypoint"))]
entrypoint!(process_instruction);

pub fn process_instruction(
    program_id: &Pubkey,
    accounts: &[AccountInfo],
    instruction_data: &[u8],
) -> ProgramResult {
    let instruction = LendingInstruction::unpack(instruction_data)?;
    Processor::process(program_id, accounts, instruction)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LendingChannelAccount {
    pub version: u8,
    pub bump: u8,
    pub quorum_threshold_percent: u8,
    pub approval_threshold_percent: u8,
    pub member_count: u16,
    pub lifecycle_state: u8,
    pub required_stake_amount: u64,
    pub stake_token_decimals: u8,
    pub created_at: i64,
    pub updated_at: i64,
    pub treasury_authority: [u8; PUBKEY_LEN],
    pub stake_token_mint: [u8; PUBKEY_LEN],
}

impl LendingChannelAccount {
    pub fn pack_into_slice(&self, dst: &mut [u8]) -> Result<(), ProgramError> {
        if dst.len() < LENDING_CHANNEL_ACCOUNT_LEN {
            return Err(ProgramError::AccountDataTooSmall);
        }
        dst.fill(0);
        let mut offset = 0;
        write_u8(dst, &mut offset, self.version)?;
        write_u8(dst, &mut offset, self.bump)?;
        write_u8(dst, &mut offset, self.quorum_threshold_percent)?;
        write_u8(dst, &mut offset, self.approval_threshold_percent)?;
        write_u16(dst, &mut offset, self.member_count)?;
        write_u8(dst, &mut offset, self.lifecycle_state)?;
        write_u64(dst, &mut offset, self.required_stake_amount)?;
        write_u8(dst, &mut offset, self.stake_token_decimals)?;
        offset += 5;
        write_i64(dst, &mut offset, self.created_at)?;
        write_i64(dst, &mut offset, self.updated_at)?;
        write_bytes(dst, &mut offset, &self.treasury_authority)?;
        write_bytes(dst, &mut offset, &self.stake_token_mint)?;
        Ok(())
    }

    pub fn unpack(src: &[u8]) -> Result<Self, ProgramError> {
        if src.len() < LENDING_CHANNEL_ACCOUNT_LEN {
            return Err(ProgramError::InvalidAccountData);
        }
        let mut offset = 0;
        let version = read_u8(src, &mut offset)?;
        let bump = read_u8(src, &mut offset)?;
        let quorum_threshold_percent = read_u8(src, &mut offset)?;
        let approval_threshold_percent = read_u8(src, &mut offset)?;
        let member_count = read_u16(src, &mut offset)?;
        let lifecycle_state = read_u8(src, &mut offset)?;
        let required_stake_amount = read_u64(src, &mut offset)?;
        let stake_token_decimals = read_u8(src, &mut offset)?;
        offset += 5;
        let created_at = read_i64(src, &mut offset)?;
        let updated_at = read_i64(src, &mut offset)?;
        let treasury_authority = read_array::<PUBKEY_LEN>(src, &mut offset)?;
        let stake_token_mint = read_array::<PUBKEY_LEN>(src, &mut offset)?;
        Ok(Self {
            version,
            bump,
            quorum_threshold_percent,
            approval_threshold_percent,
            member_count,
            lifecycle_state,
            required_stake_amount,
            stake_token_decimals,
            created_at,
            updated_at,
            treasury_authority,
            stake_token_mint,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LoanRequestAccount {
    pub version: u8,
    pub bump: u8,
    pub status: u8,
    pub borrower_type: u8,
    pub duration_days: u16,
    pub interest_bps: u16,
    pub yes_votes: u16,
    pub no_votes: u16,
    pub requested_at: i64,
    pub due_at: i64,
    pub approved_at: i64,
    pub disbursed_at: i64,
    pub repaid_at: i64,
    pub principal_amount: u64,
    pub total_repaid_amount: u64,
    pub purpose_hash: [u8; PURPOSE_HASH_LEN],
    pub channel: [u8; PUBKEY_LEN],
    pub borrower: [u8; PUBKEY_LEN],
}

impl LoanRequestAccount {
    pub fn apply_vote(&mut self, vote_choice: u8) -> Result<(), ProgramError> {
        if self.status != STATUS_PENDING {
            return Err(LendingError::LoanRequestNotPending.into());
        }
        match vote_choice {
            VOTE_YES => self.yes_votes = self.yes_votes.saturating_add(1),
            VOTE_NO => self.no_votes = self.no_votes.saturating_add(1),
            _ => return Err(LendingError::InvalidVoteChoice.into()),
        }
        Ok(())
    }

    pub fn finalize(
        &mut self,
        quorum_needed: u16,
        approval_percent: u8,
        now: i64,
    ) -> Result<(), ProgramError> {
        if self.status != STATUS_PENDING {
            return Err(LendingError::LoanRequestNotPending.into());
        }
        let total_votes = self.yes_votes.saturating_add(self.no_votes);
        if total_votes < quorum_needed {
            return Err(LendingError::QuorumNotReached.into());
        }
        let yes_percent = if total_votes == 0 {
            0
        } else {
            ((self.yes_votes as u32) * 100 / (total_votes as u32)) as u8
        };
        if yes_percent >= approval_percent {
            self.status = STATUS_APPROVED;
            self.approved_at = now;
        } else {
            self.status = STATUS_REJECTED;
        }
        Ok(())
    }

    pub fn disburse(&mut self, now: i64) -> Result<(), ProgramError> {
        if self.status != STATUS_APPROVED {
            return Err(LendingError::LoanRequestNotApproved.into());
        }
        self.status = STATUS_DISBURSED;
        self.disbursed_at = now;
        Ok(())
    }

    pub fn record_repayment(&mut self, amount: u64, now: i64) -> Result<(), ProgramError> {
        if self.status != STATUS_DISBURSED && self.status != STATUS_DEFAULTED {
            return Err(LendingError::LoanRequestNotRepayable.into());
        }
        self.total_repaid_amount = self.total_repaid_amount.saturating_add(amount);
        if self.total_repaid_amount >= self.principal_amount {
            self.status = STATUS_REPAID;
            self.repaid_at = now;
        }
        Ok(())
    }

    pub fn pack_into_slice(&self, dst: &mut [u8]) -> Result<(), ProgramError> {
        if dst.len() < LOAN_REQUEST_ACCOUNT_LEN {
            return Err(ProgramError::AccountDataTooSmall);
        }
        let mut offset = 0;
        write_u8(dst, &mut offset, self.version)?;
        write_u8(dst, &mut offset, self.bump)?;
        write_u8(dst, &mut offset, self.status)?;
        write_u8(dst, &mut offset, self.borrower_type)?;
        write_u16(dst, &mut offset, self.duration_days)?;
        write_u16(dst, &mut offset, self.interest_bps)?;
        write_u16(dst, &mut offset, self.yes_votes)?;
        write_u16(dst, &mut offset, self.no_votes)?;
        write_i64(dst, &mut offset, self.requested_at)?;
        write_i64(dst, &mut offset, self.due_at)?;
        write_i64(dst, &mut offset, self.approved_at)?;
        write_i64(dst, &mut offset, self.disbursed_at)?;
        write_i64(dst, &mut offset, self.repaid_at)?;
        write_u64(dst, &mut offset, self.principal_amount)?;
        write_u64(dst, &mut offset, self.total_repaid_amount)?;
        write_bytes(dst, &mut offset, &self.purpose_hash)?;
        write_bytes(dst, &mut offset, &self.channel)?;
        write_bytes(dst, &mut offset, &self.borrower)?;
        Ok(())
    }

    pub fn unpack(src: &[u8]) -> Result<Self, ProgramError> {
        if src.len() < LOAN_REQUEST_ACCOUNT_LEN {
            return Err(ProgramError::InvalidAccountData);
        }
        let mut offset = 0;
        Ok(Self {
            version: read_u8(src, &mut offset)?,
            bump: read_u8(src, &mut offset)?,
            status: read_u8(src, &mut offset)?,
            borrower_type: read_u8(src, &mut offset)?,
            duration_days: read_u16(src, &mut offset)?,
            interest_bps: read_u16(src, &mut offset)?,
            yes_votes: read_u16(src, &mut offset)?,
            no_votes: read_u16(src, &mut offset)?,
            requested_at: read_i64(src, &mut offset)?,
            due_at: read_i64(src, &mut offset)?,
            approved_at: read_i64(src, &mut offset)?,
            disbursed_at: read_i64(src, &mut offset)?,
            repaid_at: read_i64(src, &mut offset)?,
            principal_amount: read_u64(src, &mut offset)?,
            total_repaid_amount: read_u64(src, &mut offset)?,
            purpose_hash: read_array::<PURPOSE_HASH_LEN>(src, &mut offset)?,
            channel: read_array::<PUBKEY_LEN>(src, &mut offset)?,
            borrower: read_array::<PUBKEY_LEN>(src, &mut offset)?,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VoteRecordAccount {
    pub version: u8,
    pub bump: u8,
    pub vote_choice: u8,
    pub voted_at: i64,
    pub loan_request: [u8; PUBKEY_LEN],
    pub voter: [u8; PUBKEY_LEN],
}

impl VoteRecordAccount {
    pub fn pack_into_slice(&self, dst: &mut [u8]) -> Result<(), ProgramError> {
        if dst.len() < VOTE_RECORD_ACCOUNT_LEN {
            return Err(ProgramError::AccountDataTooSmall);
        }
        dst.fill(0);
        let mut offset = 0;
        write_u8(dst, &mut offset, self.version)?;
        write_u8(dst, &mut offset, self.bump)?;
        write_u8(dst, &mut offset, self.vote_choice)?;
        offset += 1;
        write_i64(dst, &mut offset, self.voted_at)?;
        write_bytes(dst, &mut offset, &self.loan_request)?;
        write_bytes(dst, &mut offset, &self.voter)?;
        Ok(())
    }

    pub fn unpack(src: &[u8]) -> Result<Self, ProgramError> {
        if src.len() < VOTE_RECORD_ACCOUNT_LEN {
            return Err(ProgramError::InvalidAccountData);
        }
        let mut offset = 0;
        let version = read_u8(src, &mut offset)?;
        let bump = read_u8(src, &mut offset)?;
        let vote_choice = read_u8(src, &mut offset)?;
        offset += 1;
        let voted_at = read_i64(src, &mut offset)?;
        let loan_request = read_array::<PUBKEY_LEN>(src, &mut offset)?;
        let voter = read_array::<PUBKEY_LEN>(src, &mut offset)?;
        Ok(Self {
            version,
            bump,
            vote_choice,
            voted_at,
            loan_request,
            voter,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LendingInstruction {
    InitializeChannel(InitializeChannelParams),
    CreateLoanRequest(CreateLoanRequestParams),
    CastVote(CastVoteParams),
    FinalizeLoanRequest(FinalizeLoanRequestParams),
    DisburseLoan(DisburseLoanParams),
    RecordRepayment(RecordRepaymentParams),
}

impl LendingInstruction {
    pub fn unpack(input: &[u8]) -> Result<Self, ProgramError> {
        if input.len() < 8 {
            return Err(LendingError::InvalidInstructionData.into());
        }
        let (instruction_discriminator, rest) = input.split_at(8);
        match instruction_discriminator {
            d if d == discriminator("initialize_channel").as_slice() => {
                Ok(Self::InitializeChannel(InitializeChannelParams::unpack(rest)?))
            }
            d if d == discriminator("create_loan_request").as_slice() => {
                Ok(Self::CreateLoanRequest(CreateLoanRequestParams::unpack(rest)?))
            }
            d if d == discriminator("cast_vote").as_slice() => {
                Ok(Self::CastVote(CastVoteParams::unpack(rest)?))
            }
            d if d == discriminator("finalize_loan_request").as_slice() => {
                Ok(Self::FinalizeLoanRequest(FinalizeLoanRequestParams::unpack(rest)?))
            }
            d if d == discriminator("disburse_loan").as_slice() => {
                Ok(Self::DisburseLoan(DisburseLoanParams::unpack(rest)?))
            }
            d if d == discriminator("record_repayment").as_slice() => {
                Ok(Self::RecordRepayment(RecordRepaymentParams::unpack(rest)?))
            }
            _ => Err(LendingError::InvalidInstructionData.into()),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InitializeChannelParams {
    pub lending_id: Vec<u8>,
    pub quorum_threshold_percent: u8,
    pub approval_threshold_percent: u8,
    pub member_count: u16,
    pub lifecycle_state: u8,
    pub required_stake_amount: u64,
    pub stake_token_decimals: u8,
    pub created_at: i64,
    pub treasury_authority: [u8; PUBKEY_LEN],
    pub stake_token_mint: [u8; PUBKEY_LEN],
}

impl InitializeChannelParams {
    fn unpack(input: &[u8]) -> Result<Self, ProgramError> {
        let mut offset = 0;
        let lending_id = read_vec(input, &mut offset)?;
        Ok(Self {
            lending_id,
            quorum_threshold_percent: read_u8(input, &mut offset)?,
            approval_threshold_percent: read_u8(input, &mut offset)?,
            member_count: read_u16(input, &mut offset)?,
            lifecycle_state: read_u8(input, &mut offset)?,
            required_stake_amount: read_u64(input, &mut offset)?,
            stake_token_decimals: read_u8(input, &mut offset)?,
            created_at: read_i64(input, &mut offset)?,
            treasury_authority: read_array::<PUBKEY_LEN>(input, &mut offset)?,
            stake_token_mint: read_array::<PUBKEY_LEN>(input, &mut offset)?,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CreateLoanRequestParams {
    pub request_id: Vec<u8>,
    pub borrower_type: u8,
    pub duration_days: u16,
    pub interest_bps: u16,
    pub principal_amount: u64,
    pub requested_at: i64,
    pub due_at: i64,
    pub purpose_hash: [u8; PURPOSE_HASH_LEN],
}

impl CreateLoanRequestParams {
    fn unpack(input: &[u8]) -> Result<Self, ProgramError> {
        let mut offset = 0;
        let request_id = read_vec(input, &mut offset)?;
        Ok(Self {
            request_id,
            borrower_type: read_u8(input, &mut offset)?,
            duration_days: read_u16(input, &mut offset)?,
            interest_bps: read_u16(input, &mut offset)?,
            principal_amount: read_u64(input, &mut offset)?,
            requested_at: read_i64(input, &mut offset)?,
            due_at: read_i64(input, &mut offset)?,
            purpose_hash: read_array::<PURPOSE_HASH_LEN>(input, &mut offset)?,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CastVoteParams {
    pub vote_choice: u8,
    pub voted_at: i64,
}

impl CastVoteParams {
    fn unpack(input: &[u8]) -> Result<Self, ProgramError> {
        let mut offset = 0;
        Ok(Self {
            vote_choice: read_u8(input, &mut offset)?,
            voted_at: read_i64(input, &mut offset)?,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FinalizeLoanRequestParams {
    pub finalized_at: i64,
}

impl FinalizeLoanRequestParams {
    fn unpack(input: &[u8]) -> Result<Self, ProgramError> {
        let mut offset = 0;
        Ok(Self {
            finalized_at: read_i64(input, &mut offset)?,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DisburseLoanParams {
    pub disbursed_at: i64,
}

impl DisburseLoanParams {
    fn unpack(input: &[u8]) -> Result<Self, ProgramError> {
        let mut offset = 0;
        Ok(Self {
            disbursed_at: read_i64(input, &mut offset)?,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RecordRepaymentParams {
    pub amount: u64,
    pub paid_at: i64,
}

impl RecordRepaymentParams {
    fn unpack(input: &[u8]) -> Result<Self, ProgramError> {
        let mut offset = 0;
        Ok(Self {
            amount: read_u64(input, &mut offset)?,
            paid_at: read_i64(input, &mut offset)?,
        })
    }
}

struct Processor;

impl Processor {
    fn process(
        program_id: &Pubkey,
        accounts: &[AccountInfo],
        instruction: LendingInstruction,
    ) -> ProgramResult {
        match instruction {
            LendingInstruction::InitializeChannel(params) => {
                Self::process_initialize_channel(program_id, accounts, params)
            }
            LendingInstruction::CreateLoanRequest(params) => {
                Self::process_create_loan_request(program_id, accounts, params)
            }
            LendingInstruction::CastVote(params) => {
                Self::process_cast_vote(program_id, accounts, params)
            }
            LendingInstruction::FinalizeLoanRequest(params) => {
                Self::process_finalize_loan_request(program_id, accounts, params)
            }
            LendingInstruction::DisburseLoan(params) => {
                Self::process_disburse_loan(program_id, accounts, params)
            }
            LendingInstruction::RecordRepayment(params) => {
                Self::process_record_repayment(program_id, accounts, params)
            }
        }
    }

    fn process_initialize_channel(
        program_id: &Pubkey,
        accounts: &[AccountInfo],
        params: InitializeChannelParams,
    ) -> ProgramResult {
        let account_iter = &mut accounts.iter();
        let payer = next_account_info(account_iter)?;
        let channel_account = next_account_info(account_iter)?;
        let system_program_account = next_account_info(account_iter)?;

        require_signer(payer)?;
        require_writable(channel_account)?;

        if !(1..=100).contains(&params.quorum_threshold_percent)
            || !(1..=100).contains(&params.approval_threshold_percent)
        {
            return Err(LendingError::InvalidThreshold.into());
        }

        let lending_id = parse_utf8_id(&params.lending_id)?;
        let (expected_channel, bump) =
            Pubkey::find_program_address(&[LENDING_CHANNEL_SEED, lending_id.as_bytes()], program_id);
        if expected_channel != *channel_account.key {
            return Err(LendingError::InvalidPda.into());
        }

        ensure_pda_account(
            program_id,
            payer,
            channel_account,
            system_program_account,
            &[LENDING_CHANNEL_SEED, lending_id.as_bytes(), &[bump]],
            LENDING_CHANNEL_ACCOUNT_LEN,
        )?;

        let current = LendingChannelAccount::unpack(&channel_account.try_borrow_data()?)?;
        if current.version != 0 {
            return Err(LendingError::AlreadyInitialized.into());
        }

        let channel = LendingChannelAccount {
            version: 1,
            bump,
            quorum_threshold_percent: params.quorum_threshold_percent,
            approval_threshold_percent: params.approval_threshold_percent,
            member_count: params.member_count,
            lifecycle_state: params.lifecycle_state,
            required_stake_amount: params.required_stake_amount,
            stake_token_decimals: params.stake_token_decimals,
            created_at: params.created_at,
            updated_at: params.created_at,
            treasury_authority: params.treasury_authority,
            stake_token_mint: params.stake_token_mint,
        };
        channel.pack_into_slice(&mut channel_account.try_borrow_mut_data()?)?;
        Ok(())
    }

    fn process_create_loan_request(
        program_id: &Pubkey,
        accounts: &[AccountInfo],
        params: CreateLoanRequestParams,
    ) -> ProgramResult {
        let account_iter = &mut accounts.iter();
        let borrower = next_account_info(account_iter)?;
        let channel_account = next_account_info(account_iter)?;
        let loan_account = next_account_info(account_iter)?;
        let system_program_account = next_account_info(account_iter)?;

        require_signer(borrower)?;
        require_program_owned(program_id, channel_account)?;
        require_writable(loan_account)?;

        if params.principal_amount == 0 || params.duration_days == 0 {
            return Err(LendingError::InvalidLoanTerms.into());
        }
        if params.borrower_type != BORROWER_INDIVIDUAL && params.borrower_type != BORROWER_GROUP {
            return Err(LendingError::InvalidBorrowerType.into());
        }

        let channel = LendingChannelAccount::unpack(&channel_account.try_borrow_data()?)?;
        if channel.version == 0 {
            return Err(LendingError::ChannelNotInitialized.into());
        }
        if channel.lifecycle_state != CHANNEL_ACTIVE {
            return Err(LendingError::ChannelNotActive.into());
        }

        let request_id = parse_utf8_id(&params.request_id)?;
        let (expected_loan, bump) = Pubkey::find_program_address(
            &[LOAN_REQUEST_SEED, channel_account.key.as_ref(), request_id.as_bytes()],
            program_id,
        );
        if expected_loan != *loan_account.key {
            return Err(LendingError::InvalidPda.into());
        }

        ensure_pda_account(
            program_id,
            borrower,
            loan_account,
            system_program_account,
            &[
                LOAN_REQUEST_SEED,
                channel_account.key.as_ref(),
                request_id.as_bytes(),
                &[bump],
            ],
            LOAN_REQUEST_ACCOUNT_LEN,
        )?;

        let current = LoanRequestAccount::unpack(&loan_account.try_borrow_data()?)?;
        if current.version != 0 {
            return Err(LendingError::AlreadyInitialized.into());
        }

        let loan = LoanRequestAccount {
            version: 1,
            bump,
            status: STATUS_PENDING,
            borrower_type: params.borrower_type,
            duration_days: params.duration_days,
            interest_bps: params.interest_bps,
            yes_votes: 0,
            no_votes: 0,
            requested_at: params.requested_at,
            due_at: params.due_at,
            approved_at: 0,
            disbursed_at: 0,
            repaid_at: 0,
            principal_amount: params.principal_amount,
            total_repaid_amount: 0,
            purpose_hash: params.purpose_hash,
            channel: channel_account.key.to_bytes(),
            borrower: borrower.key.to_bytes(),
        };
        loan.pack_into_slice(&mut loan_account.try_borrow_mut_data()?)?;
        Ok(())
    }

    fn process_cast_vote(
        program_id: &Pubkey,
        accounts: &[AccountInfo],
        params: CastVoteParams,
    ) -> ProgramResult {
        let account_iter = &mut accounts.iter();
        let voter = next_account_info(account_iter)?;
        let _channel_account = next_account_info(account_iter)?;
        let loan_account = next_account_info(account_iter)?;
        let vote_record_account = next_account_info(account_iter)?;
        let system_program_account = next_account_info(account_iter)?;

        require_signer(voter)?;
        require_program_owned(program_id, loan_account)?;
        require_writable(loan_account)?;
        require_writable(vote_record_account)?;

        let mut loan = LoanRequestAccount::unpack(&loan_account.try_borrow_data()?)?;
        let (expected_vote_record, bump) = Pubkey::find_program_address(
            &[VOTE_RECORD_SEED, loan_account.key.as_ref(), voter.key.as_ref()],
            program_id,
        );
        if expected_vote_record != *vote_record_account.key {
            return Err(LendingError::InvalidPda.into());
        }

        ensure_pda_account(
            program_id,
            voter,
            vote_record_account,
            system_program_account,
            &[
                VOTE_RECORD_SEED,
                loan_account.key.as_ref(),
                voter.key.as_ref(),
                &[bump],
            ],
            VOTE_RECORD_ACCOUNT_LEN,
        )?;

        let current_vote = VoteRecordAccount::unpack(&vote_record_account.try_borrow_data()?)?;
        if current_vote.version != 0 {
            return Err(LendingError::DuplicateVote.into());
        }

        loan.apply_vote(params.vote_choice)?;
        loan.pack_into_slice(&mut loan_account.try_borrow_mut_data()?)?;

        let vote_record = VoteRecordAccount {
            version: 1,
            bump,
            vote_choice: params.vote_choice,
            voted_at: params.voted_at,
            loan_request: loan_account.key.to_bytes(),
            voter: voter.key.to_bytes(),
        };
        vote_record.pack_into_slice(&mut vote_record_account.try_borrow_mut_data()?)?;
        Ok(())
    }

    fn process_finalize_loan_request(
        program_id: &Pubkey,
        accounts: &[AccountInfo],
        params: FinalizeLoanRequestParams,
    ) -> ProgramResult {
        let account_iter = &mut accounts.iter();
        let signer = next_account_info(account_iter)?;
        let channel_account = next_account_info(account_iter)?;
        let loan_account = next_account_info(account_iter)?;

        require_signer(signer)?;
        require_program_owned(program_id, channel_account)?;
        require_program_owned(program_id, loan_account)?;
        require_writable(loan_account)?;

        let channel = LendingChannelAccount::unpack(&channel_account.try_borrow_data()?)?;
        let mut loan = LoanRequestAccount::unpack(&loan_account.try_borrow_data()?)?;
        if loan.channel != channel_account.key.to_bytes() {
            return Err(LendingError::ChannelLoanMismatch.into());
        }

        let quorum_needed = quorum_needed(channel.member_count, channel.quorum_threshold_percent);
        loan.finalize(quorum_needed, channel.approval_threshold_percent, params.finalized_at)?;
        loan.pack_into_slice(&mut loan_account.try_borrow_mut_data()?)?;
        Ok(())
    }

    fn process_disburse_loan(
        program_id: &Pubkey,
        accounts: &[AccountInfo],
        params: DisburseLoanParams,
    ) -> ProgramResult {
        let account_iter = &mut accounts.iter();
        let signer = next_account_info(account_iter)?;
        let _channel_account = next_account_info(account_iter)?;
        let loan_account = next_account_info(account_iter)?;

        require_signer(signer)?;
        require_program_owned(program_id, loan_account)?;
        require_writable(loan_account)?;

        let mut loan = LoanRequestAccount::unpack(&loan_account.try_borrow_data()?)?;
        loan.disburse(params.disbursed_at)?;
        loan.pack_into_slice(&mut loan_account.try_borrow_mut_data()?)?;
        Ok(())
    }

    fn process_record_repayment(
        program_id: &Pubkey,
        accounts: &[AccountInfo],
        params: RecordRepaymentParams,
    ) -> ProgramResult {
        let account_iter = &mut accounts.iter();
        let signer = next_account_info(account_iter)?;
        let _channel_account = next_account_info(account_iter)?;
        let loan_account = next_account_info(account_iter)?;

        require_signer(signer)?;
        require_program_owned(program_id, loan_account)?;
        require_writable(loan_account)?;

        let mut loan = LoanRequestAccount::unpack(&loan_account.try_borrow_data()?)?;
        loan.record_repayment(params.amount, params.paid_at)?;
        loan.pack_into_slice(&mut loan_account.try_borrow_mut_data()?)?;
        Ok(())
    }
}

#[repr(u32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LendingError {
    InvalidInstructionData = 1,
    InvalidPda = 2,
    InvalidThreshold = 3,
    AlreadyInitialized = 4,
    ChannelNotInitialized = 5,
    ChannelNotActive = 6,
    InvalidLoanTerms = 7,
    InvalidBorrowerType = 8,
    LoanRequestNotPending = 9,
    InvalidVoteChoice = 10,
    DuplicateVote = 11,
    QuorumNotReached = 12,
    LoanRequestNotApproved = 13,
    LoanRequestNotRepayable = 14,
    AccountNotWritable = 15,
    MissingSignature = 16,
    AccountNotProgramOwned = 17,
    ChannelLoanMismatch = 18,
}

impl From<LendingError> for ProgramError {
    fn from(value: LendingError) -> Self {
        ProgramError::Custom(value as u32)
    }
}

fn require_signer(account: &AccountInfo) -> ProgramResult {
    if !account.is_signer {
        return Err(LendingError::MissingSignature.into());
    }
    Ok(())
}

fn require_writable(account: &AccountInfo) -> ProgramResult {
    if !account.is_writable {
        return Err(LendingError::AccountNotWritable.into());
    }
    Ok(())
}

fn require_program_owned(program_id: &Pubkey, account: &AccountInfo) -> ProgramResult {
    if account.owner != program_id {
        msg!("expected program owned account {}", account.key);
        return Err(LendingError::AccountNotProgramOwned.into());
    }
    Ok(())
}

fn quorum_needed(member_count: u16, quorum_threshold_percent: u8) -> u16 {
    let member_count = member_count.max(1) as u32;
    let numerator = member_count * (quorum_threshold_percent as u32);
    ((numerator + 99) / 100).max(1) as u16
}

fn ensure_pda_account<'a>(
    program_id: &Pubkey,
    payer: &AccountInfo<'a>,
    pda_account: &AccountInfo<'a>,
    system_program_account: &AccountInfo<'a>,
    signer_seeds: &[&[u8]],
    space: usize,
) -> ProgramResult {
    if pda_account.owner == program_id {
        return Ok(());
    }
    if *system_program_account.key != system_program::id() {
        return Err(LendingError::InvalidInstructionData.into());
    }
    if *pda_account.owner != system_program::id() {
        return Err(LendingError::AccountNotProgramOwned.into());
    }
    let rent = Rent::get()?;
    let lamports = rent.minimum_balance(space);
    invoke_signed(
        &system_instruction::create_account(
            payer.key,
            pda_account.key,
            lamports,
            space as u64,
            program_id,
        ),
        &[payer.clone(), pda_account.clone(), system_program_account.clone()],
        &[signer_seeds],
    )?;
    Ok(())
}

fn discriminator(name: &str) -> [u8; 8] {
    let hash = solana_program::hash::hash(format!("global:{name}").as_bytes());
    let mut out = [0u8; 8];
    out.copy_from_slice(&hash.to_bytes()[..8]);
    out
}

fn parse_utf8_id(input: &[u8]) -> Result<String, ProgramError> {
    if input.is_empty() || input.len() > MAX_ID_LEN {
        return Err(LendingError::InvalidInstructionData.into());
    }
    core::str::from_utf8(input)
        .map(|value| value.to_string())
        .map_err(|_| LendingError::InvalidInstructionData.into())
}

fn read_vec(src: &[u8], offset: &mut usize) -> Result<Vec<u8>, ProgramError> {
    let len = read_u16(src, offset)? as usize;
    if len == 0 || len > MAX_ID_LEN || *offset + len > src.len() {
        return Err(LendingError::InvalidInstructionData.into());
    }
    let value = src[*offset..*offset + len].to_vec();
    *offset += len;
    Ok(value)
}

fn read_array<const N: usize>(src: &[u8], offset: &mut usize) -> Result<[u8; N], ProgramError> {
    if *offset + N > src.len() {
        return Err(LendingError::InvalidInstructionData.into());
    }
    let mut out = [0u8; N];
    out.copy_from_slice(&src[*offset..*offset + N]);
    *offset += N;
    Ok(out)
}

fn read_u8(src: &[u8], offset: &mut usize) -> Result<u8, ProgramError> {
    if *offset + 1 > src.len() {
        return Err(LendingError::InvalidInstructionData.into());
    }
    let value = src[*offset];
    *offset += 1;
    Ok(value)
}

fn read_u16(src: &[u8], offset: &mut usize) -> Result<u16, ProgramError> {
    if *offset + 2 > src.len() {
        return Err(LendingError::InvalidInstructionData.into());
    }
    let value = u16::from_le_bytes([src[*offset], src[*offset + 1]]);
    *offset += 2;
    Ok(value)
}

fn read_u64(src: &[u8], offset: &mut usize) -> Result<u64, ProgramError> {
    if *offset + 8 > src.len() {
        return Err(LendingError::InvalidInstructionData.into());
    }
    let value = u64::from_le_bytes(src[*offset..*offset + 8].try_into().unwrap());
    *offset += 8;
    Ok(value)
}

fn read_i64(src: &[u8], offset: &mut usize) -> Result<i64, ProgramError> {
    if *offset + 8 > src.len() {
        return Err(LendingError::InvalidInstructionData.into());
    }
    let value = i64::from_le_bytes(src[*offset..*offset + 8].try_into().unwrap());
    *offset += 8;
    Ok(value)
}

fn write_u8(dst: &mut [u8], offset: &mut usize, value: u8) -> Result<(), ProgramError> {
    if *offset + 1 > dst.len() {
        return Err(ProgramError::AccountDataTooSmall);
    }
    dst[*offset] = value;
    *offset += 1;
    Ok(())
}

fn write_u16(dst: &mut [u8], offset: &mut usize, value: u16) -> Result<(), ProgramError> {
    if *offset + 2 > dst.len() {
        return Err(ProgramError::AccountDataTooSmall);
    }
    dst[*offset..*offset + 2].copy_from_slice(&value.to_le_bytes());
    *offset += 2;
    Ok(())
}

fn write_u64(dst: &mut [u8], offset: &mut usize, value: u64) -> Result<(), ProgramError> {
    if *offset + 8 > dst.len() {
        return Err(ProgramError::AccountDataTooSmall);
    }
    dst[*offset..*offset + 8].copy_from_slice(&value.to_le_bytes());
    *offset += 8;
    Ok(())
}

fn write_i64(dst: &mut [u8], offset: &mut usize, value: i64) -> Result<(), ProgramError> {
    if *offset + 8 > dst.len() {
        return Err(ProgramError::AccountDataTooSmall);
    }
    dst[*offset..*offset + 8].copy_from_slice(&value.to_le_bytes());
    *offset += 8;
    Ok(())
}

fn write_bytes(dst: &mut [u8], offset: &mut usize, value: &[u8]) -> Result<(), ProgramError> {
    if *offset + value.len() > dst.len() {
        return Err(ProgramError::AccountDataTooSmall);
    }
    dst[*offset..*offset + value.len()].copy_from_slice(value);
    *offset += value.len();
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use solana_program::clock::Epoch;

    struct TestAccount {
        key: Pubkey,
        lamports: u64,
        data: Vec<u8>,
        owner: Pubkey,
        is_signer: bool,
        is_writable: bool,
    }

    impl TestAccount {
        fn new(owner: Pubkey, data_len: usize, is_signer: bool, is_writable: bool) -> Self {
            Self {
                key: Pubkey::new_unique(),
                lamports: 1_000_000,
                data: vec![0u8; data_len],
                owner,
                is_signer,
                is_writable,
            }
        }

        fn with_key(
            key: Pubkey,
            owner: Pubkey,
            data_len: usize,
            is_signer: bool,
            is_writable: bool,
        ) -> Self {
            Self {
                key,
                lamports: 1_000_000,
                data: vec![0u8; data_len],
                owner,
                is_signer,
                is_writable,
            }
        }

        fn info(&mut self) -> AccountInfo<'_> {
            AccountInfo::new(
                &self.key,
                self.is_signer,
                self.is_writable,
                &mut self.lamports,
                self.data.as_mut_slice(),
                &self.owner,
                false,
                Epoch::default(),
            )
        }
    }

    fn encode_initialize_channel(params: &InitializeChannelParams) -> Vec<u8> {
        let mut out = discriminator("initialize_channel").to_vec();
        out.extend_from_slice(&(params.lending_id.len() as u16).to_le_bytes());
        out.extend_from_slice(&params.lending_id);
        out.push(params.quorum_threshold_percent);
        out.push(params.approval_threshold_percent);
        out.extend_from_slice(&params.member_count.to_le_bytes());
        out.push(params.lifecycle_state);
        out.extend_from_slice(&params.required_stake_amount.to_le_bytes());
        out.push(params.stake_token_decimals);
        out.extend_from_slice(&params.created_at.to_le_bytes());
        out.extend_from_slice(&params.treasury_authority);
        out.extend_from_slice(&params.stake_token_mint);
        out
    }

    fn encode_create_loan_request(params: &CreateLoanRequestParams) -> Vec<u8> {
        let mut out = discriminator("create_loan_request").to_vec();
        out.extend_from_slice(&(params.request_id.len() as u16).to_le_bytes());
        out.extend_from_slice(&params.request_id);
        out.push(params.borrower_type);
        out.extend_from_slice(&params.duration_days.to_le_bytes());
        out.extend_from_slice(&params.interest_bps.to_le_bytes());
        out.extend_from_slice(&params.principal_amount.to_le_bytes());
        out.extend_from_slice(&params.requested_at.to_le_bytes());
        out.extend_from_slice(&params.due_at.to_le_bytes());
        out.extend_from_slice(&params.purpose_hash);
        out
    }

    fn encode_cast_vote(vote_choice: u8, voted_at: i64) -> Vec<u8> {
        let mut out = discriminator("cast_vote").to_vec();
        out.push(vote_choice);
        out.extend_from_slice(&voted_at.to_le_bytes());
        out
    }

    fn encode_finalize(finalized_at: i64) -> Vec<u8> {
        let mut out = discriminator("finalize_loan_request").to_vec();
        out.extend_from_slice(&finalized_at.to_le_bytes());
        out
    }

    fn encode_disburse(disbursed_at: i64) -> Vec<u8> {
        let mut out = discriminator("disburse_loan").to_vec();
        out.extend_from_slice(&disbursed_at.to_le_bytes());
        out
    }

    fn encode_record_repayment(amount: u64, paid_at: i64) -> Vec<u8> {
        let mut out = discriminator("record_repayment").to_vec();
        out.extend_from_slice(&amount.to_le_bytes());
        out.extend_from_slice(&paid_at.to_le_bytes());
        out
    }

    fn init_channel(program_id: &Pubkey, lending_id: &str, member_count: u16) -> (TestAccount, TestAccount) {
        let mut payer = TestAccount::new(Pubkey::new_unique(), 0, true, true);
        let mut system_program_account =
            TestAccount::with_key(system_program::id(), system_program::id(), 0, false, false);
        let (channel_key, _) =
            Pubkey::find_program_address(&[LENDING_CHANNEL_SEED, lending_id.as_bytes()], program_id);
        let mut channel = TestAccount::with_key(
            channel_key,
            *program_id,
            LENDING_CHANNEL_ACCOUNT_LEN,
            false,
            true,
        );
        let params = InitializeChannelParams {
            lending_id: lending_id.as_bytes().to_vec(),
            quorum_threshold_percent: 60,
            approval_threshold_percent: 50,
            member_count,
            lifecycle_state: CHANNEL_ACTIVE,
            required_stake_amount: 100,
            stake_token_decimals: 6,
            created_at: 1_700_000_000,
            treasury_authority: Pubkey::new_unique().to_bytes(),
            stake_token_mint: Pubkey::new_unique().to_bytes(),
        };
        let payer_info = payer.info();
        let channel_info = channel.info();
        let system_info = system_program_account.info();
        process_instruction(
            program_id,
            &[payer_info, channel_info, system_info],
            &encode_initialize_channel(&params),
        )
        .unwrap();
        (payer, channel)
    }

    #[test]
    fn create_loan_request_succeeds_for_initialized_channel() {
        let program_id = id();
        let (_payer, mut channel) = init_channel(&program_id, "LEND-1", 3);
        let mut borrower = TestAccount::new(Pubkey::new_unique(), 0, true, true);
        let request_id = "REQ-1";
        let (loan_key, _) = Pubkey::find_program_address(
            &[LOAN_REQUEST_SEED, channel.key.as_ref(), request_id.as_bytes()],
            &program_id,
        );
        let mut loan = TestAccount::with_key(loan_key, program_id, LOAN_REQUEST_ACCOUNT_LEN, false, true);
        let params = CreateLoanRequestParams {
            request_id: request_id.as_bytes().to_vec(),
            borrower_type: BORROWER_INDIVIDUAL,
            duration_days: 7,
            interest_bps: 500,
            principal_amount: 1_000_000,
            requested_at: 1_700_000_100,
            due_at: 1_700_604_900,
            purpose_hash: [9; PURPOSE_HASH_LEN],
        };

        let mut system_program_account =
            TestAccount::with_key(system_program::id(), system_program::id(), 0, false, false);
        process_instruction(
            &program_id,
            &[borrower.info(), channel.info(), loan.info(), system_program_account.info()],
            &encode_create_loan_request(&params),
        )
        .unwrap();

        let loan_state = LoanRequestAccount::unpack(&loan.data).unwrap();
        assert_eq!(loan_state.status, STATUS_PENDING);
        assert_eq!(loan_state.borrower, borrower.key.to_bytes());
        assert_eq!(loan_state.channel, channel.key.to_bytes());
    }

    #[test]
    fn duplicate_vote_record_is_rejected() {
        let program_id = id();
        let (_payer, mut channel) = init_channel(&program_id, "LEND-2", 3);
        let mut borrower = TestAccount::new(Pubkey::new_unique(), 0, true, true);
        let request_id = "REQ-2";
        let (loan_key, _) = Pubkey::find_program_address(
            &[LOAN_REQUEST_SEED, channel.key.as_ref(), request_id.as_bytes()],
            &program_id,
        );
        let mut loan = TestAccount::with_key(loan_key, program_id, LOAN_REQUEST_ACCOUNT_LEN, false, true);
        let mut system_program_account =
            TestAccount::with_key(system_program::id(), system_program::id(), 0, false, false);
        process_instruction(
            &program_id,
            &[borrower.info(), channel.info(), loan.info(), system_program_account.info()],
            &encode_create_loan_request(&CreateLoanRequestParams {
                request_id: request_id.as_bytes().to_vec(),
                borrower_type: BORROWER_INDIVIDUAL,
                duration_days: 7,
                interest_bps: 500,
                principal_amount: 1_000_000,
                requested_at: 1_700_000_100,
                due_at: 1_700_604_900,
                purpose_hash: [9; PURPOSE_HASH_LEN],
            }),
        )
        .unwrap();

        let mut voter = TestAccount::new(Pubkey::new_unique(), 0, true, true);
        let (vote_key, _) = Pubkey::find_program_address(
            &[VOTE_RECORD_SEED, loan.key.as_ref(), voter.key.as_ref()],
            &program_id,
        );
        let mut vote_record =
            TestAccount::with_key(vote_key, program_id, VOTE_RECORD_ACCOUNT_LEN, false, true);

        process_instruction(
            &program_id,
            &[
                voter.info(),
                channel.info(),
                loan.info(),
                vote_record.info(),
                system_program_account.info(),
            ],
            &encode_cast_vote(VOTE_YES, 1_700_000_200),
        )
        .unwrap();

        let err = process_instruction(
            &program_id,
            &[
                voter.info(),
                channel.info(),
                loan.info(),
                vote_record.info(),
                system_program_account.info(),
            ],
            &encode_cast_vote(VOTE_YES, 1_700_000_201),
        )
        .unwrap_err();
        assert_eq!(err, LendingError::DuplicateVote.into());
    }

    #[test]
    fn finalize_respects_quorum_and_thresholds() {
        let program_id = id();
        let (_payer, mut channel) = init_channel(&program_id, "LEND-3", 3);
        let mut borrower = TestAccount::new(Pubkey::new_unique(), 0, true, true);
        let request_id = "REQ-3";
        let (loan_key, _) = Pubkey::find_program_address(
            &[LOAN_REQUEST_SEED, channel.key.as_ref(), request_id.as_bytes()],
            &program_id,
        );
        let mut loan = TestAccount::with_key(loan_key, program_id, LOAN_REQUEST_ACCOUNT_LEN, false, true);
        let mut system_program_account =
            TestAccount::with_key(system_program::id(), system_program::id(), 0, false, false);
        process_instruction(
            &program_id,
            &[borrower.info(), channel.info(), loan.info(), system_program_account.info()],
            &encode_create_loan_request(&CreateLoanRequestParams {
                request_id: request_id.as_bytes().to_vec(),
                borrower_type: BORROWER_INDIVIDUAL,
                duration_days: 7,
                interest_bps: 500,
                principal_amount: 1_000_000,
                requested_at: 1_700_000_100,
                due_at: 1_700_604_900,
                purpose_hash: [9; PURPOSE_HASH_LEN],
            }),
        )
        .unwrap();

        for _ in 0..2 {
            let mut voter = TestAccount::new(Pubkey::new_unique(), 0, true, true);
            let (vote_key, _) = Pubkey::find_program_address(
                &[VOTE_RECORD_SEED, loan.key.as_ref(), voter.key.as_ref()],
                &program_id,
            );
            let mut vote_record =
                TestAccount::with_key(vote_key, program_id, VOTE_RECORD_ACCOUNT_LEN, false, true);
            process_instruction(
                &program_id,
                &[
                    voter.info(),
                    channel.info(),
                    loan.info(),
                    vote_record.info(),
                    system_program_account.info(),
                ],
                &encode_cast_vote(VOTE_YES, 1_700_000_200),
            )
            .unwrap();
        }

        let mut finalizer = TestAccount::new(Pubkey::new_unique(), 0, true, true);
        process_instruction(
            &program_id,
            &[finalizer.info(), channel.info(), loan.info()],
            &encode_finalize(1_700_000_300),
        )
        .unwrap();

        let loan_state = LoanRequestAccount::unpack(&loan.data).unwrap();
        assert_eq!(loan_state.status, STATUS_APPROVED);
        assert_eq!(loan_state.approved_at, 1_700_000_300);
    }

    #[test]
    fn disbursement_requires_approval() {
        let program_id = id();
        let (_payer, mut channel) = init_channel(&program_id, "LEND-4", 2);
        let mut borrower = TestAccount::new(Pubkey::new_unique(), 0, true, true);
        let request_id = "REQ-4";
        let (loan_key, _) = Pubkey::find_program_address(
            &[LOAN_REQUEST_SEED, channel.key.as_ref(), request_id.as_bytes()],
            &program_id,
        );
        let mut loan = TestAccount::with_key(loan_key, program_id, LOAN_REQUEST_ACCOUNT_LEN, false, true);
        let mut system_program_account =
            TestAccount::with_key(system_program::id(), system_program::id(), 0, false, false);
        process_instruction(
            &program_id,
            &[borrower.info(), channel.info(), loan.info(), system_program_account.info()],
            &encode_create_loan_request(&CreateLoanRequestParams {
                request_id: request_id.as_bytes().to_vec(),
                borrower_type: BORROWER_INDIVIDUAL,
                duration_days: 7,
                interest_bps: 500,
                principal_amount: 1_000_000,
                requested_at: 1_700_000_100,
                due_at: 1_700_604_900,
                purpose_hash: [9; PURPOSE_HASH_LEN],
            }),
        )
        .unwrap();

        let mut operator = TestAccount::new(Pubkey::new_unique(), 0, true, true);
        let err = process_instruction(
            &program_id,
            &[operator.info(), channel.info(), loan.info()],
            &encode_disburse(1_700_000_500),
        )
        .unwrap_err();
        assert_eq!(err, LendingError::LoanRequestNotApproved.into());
    }

    #[test]
    fn repayment_closes_request_when_principal_is_covered() {
        let program_id = id();
        let (_payer, mut channel) = init_channel(&program_id, "LEND-5", 2);
        let mut borrower = TestAccount::new(Pubkey::new_unique(), 0, true, true);
        let request_id = "REQ-5";
        let (loan_key, _) = Pubkey::find_program_address(
            &[LOAN_REQUEST_SEED, channel.key.as_ref(), request_id.as_bytes()],
            &program_id,
        );
        let mut loan = TestAccount::with_key(loan_key, program_id, LOAN_REQUEST_ACCOUNT_LEN, false, true);
        let mut system_program_account =
            TestAccount::with_key(system_program::id(), system_program::id(), 0, false, false);
        process_instruction(
            &program_id,
            &[borrower.info(), channel.info(), loan.info(), system_program_account.info()],
            &encode_create_loan_request(&CreateLoanRequestParams {
                request_id: request_id.as_bytes().to_vec(),
                borrower_type: BORROWER_INDIVIDUAL,
                duration_days: 7,
                interest_bps: 500,
                principal_amount: 1_000_000,
                requested_at: 1_700_000_100,
                due_at: 1_700_604_900,
                purpose_hash: [9; PURPOSE_HASH_LEN],
            }),
        )
        .unwrap();

        for _ in 0..2 {
            let mut voter = TestAccount::new(Pubkey::new_unique(), 0, true, true);
            let (vote_key, _) = Pubkey::find_program_address(
                &[VOTE_RECORD_SEED, loan.key.as_ref(), voter.key.as_ref()],
                &program_id,
            );
            let mut vote_record =
                TestAccount::with_key(vote_key, program_id, VOTE_RECORD_ACCOUNT_LEN, false, true);
            process_instruction(
                &program_id,
                &[
                    voter.info(),
                    channel.info(),
                    loan.info(),
                    vote_record.info(),
                    system_program_account.info(),
                ],
                &encode_cast_vote(VOTE_YES, 1_700_000_200),
            )
            .unwrap();
        }

        let mut operator = TestAccount::new(Pubkey::new_unique(), 0, true, true);
        process_instruction(
            &program_id,
            &[operator.info(), channel.info(), loan.info()],
            &encode_finalize(1_700_000_300),
        )
        .unwrap();
        process_instruction(
            &program_id,
            &[operator.info(), channel.info(), loan.info()],
            &encode_disburse(1_700_000_400),
        )
        .unwrap();
        process_instruction(
            &program_id,
            &[operator.info(), channel.info(), loan.info()],
            &encode_record_repayment(500_000, 1_700_000_500),
        )
        .unwrap();
        process_instruction(
            &program_id,
            &[operator.info(), channel.info(), loan.info()],
            &encode_record_repayment(500_000, 1_700_000_600),
        )
        .unwrap();

        let loan_state = LoanRequestAccount::unpack(&loan.data).unwrap();
        assert_eq!(loan_state.status, STATUS_REPAID);
        assert_eq!(loan_state.total_repaid_amount, 1_000_000);
    }
}
