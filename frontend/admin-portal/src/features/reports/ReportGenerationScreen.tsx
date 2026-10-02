import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Grid,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { saveBlob } from '@lib/download';
import { useExportReport, useReportTypes, useRequestReport } from './hooks';
import type { ReportFormat, ReportRequestPayload, ReportResult } from './api';

/** Number of days between two ISO dates, inclusive-ish (for the async note). */
function rangeDays(from: string, to: string): number {
  const a = new Date(from).getTime();
  const b = new Date(to).getTime();
  if (!Number.isFinite(a) || !Number.isFinite(b)) return 0;
  return Math.round((b - a) / (1000 * 60 * 60 * 24));
}

/** The message to show for a result, with a sensible default per mode. */
function resultMessage(result: ReportResult): string {
  if (result.message) return result.message;
  return result.mode === 'QUEUED'
    ? 'Your report is being generated and will be emailed to you when ready.'
    : 'Your report is ready.';
}

/**
 * Report Generation module (Requirement 19.2, Requirement 20). Lets an admin
 * pick a pre-built report, date range, and export format. Ranges of 7 days or
 * fewer can be downloaded straight away; longer ranges are generated
 * asynchronously and delivered by email (Requirement 20.2/20.3). The request
 * only acknowledges; the file is fetched from the export endpoint when the
 * user asks to download it, and only offered when the report is ready (SYNC).
 */
export function ReportGenerationScreen() {
  const typesQuery = useReportTypes();
  const request = useRequestReport();
  const download = useExportReport();

  const [reportTypeId, setReportTypeId] = useState('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [format, setFormat] = useState<ReportFormat>('PDF');
  const [touched, setTouched] = useState(false);

  const days = rangeDays(fromDate, toDate);
  const willBeAsync = days > 7;
  const rangeInvalid = !fromDate || !toDate || days < 0;
  const formInvalid = !reportTypeId || rangeInvalid;

  const handleGenerate = () => {
    setTouched(true);
    if (formInvalid) return;
    download.reset();
    request.mutate({ reportTypeId, fromDate, toDate, format });
  };

  const handleDownload = (payload: ReportRequestPayload) => {
    download.mutate(payload, {
      onSuccess: (result) => {
        if (result.kind === 'file') saveBlob(result.blob, result.fileName);
      },
    });
  };

  return (
    <ModuleScreen
      title="Report Generation"
      description="Generate revenue, performance, and operational reports."
    >
      <QueryStateView
        isLoading={typesQuery.isLoading}
        isError={typesQuery.isError}
        error={typesQuery.error}
        onRetry={() => void typesQuery.refetch()}
      >
        <Card variant="outlined" sx={{ maxWidth: 720 }}>
          <CardContent>
            <Grid container spacing={2}>
              <Grid item xs={12}>
                <TextField
                  select
                  label="Report type"
                  value={reportTypeId}
                  onChange={(event) => setReportTypeId(event.target.value)}
                  fullWidth
                  error={touched && !reportTypeId}
                  helperText={touched && !reportTypeId ? 'Select a report type.' : undefined}
                >
                  {(typesQuery.data ?? []).map((type) => (
                    <MenuItem key={type.id} value={type.id}>
                      <Stack direction="row" spacing={1} alignItems="center">
                        <span>{type.name}</span>
                        {type.financeOnly ? (
                          <Chip size="small" label="Finance" color="secondary" />
                        ) : null}
                      </Stack>
                    </MenuItem>
                  ))}
                </TextField>
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  label="From"
                  type="date"
                  value={fromDate}
                  onChange={(event) => setFromDate(event.target.value)}
                  InputLabelProps={{ shrink: true }}
                  fullWidth
                  error={touched && !fromDate}
                />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  label="To"
                  type="date"
                  value={toDate}
                  onChange={(event) => setToDate(event.target.value)}
                  InputLabelProps={{ shrink: true }}
                  fullWidth
                  error={touched && (!toDate || days < 0)}
                  helperText={touched && days < 0 ? 'To date must be after from date.' : undefined}
                />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  select
                  label="Format"
                  value={format}
                  onChange={(event) => setFormat(event.target.value as ReportFormat)}
                  fullWidth
                >
                  <MenuItem value="PDF">PDF</MenuItem>
                  <MenuItem value="CSV">CSV</MenuItem>
                </TextField>
              </Grid>
            </Grid>

            {willBeAsync ? (
              <Alert severity="info" sx={{ mt: 2 }}>
                Ranges longer than 7 days are generated in the background and emailed to you when
                ready.
              </Alert>
            ) : null}

            <Box sx={{ mt: 2 }}>
              <Button variant="contained" onClick={handleGenerate} disabled={request.isPending}>
                {request.isPending ? 'Generating…' : 'Generate report'}
              </Button>
            </Box>

            {request.isError ? (
              <Alert severity="error" sx={{ mt: 2 }}>
                {request.error.message}
              </Alert>
            ) : null}

            {request.isSuccess ? (
              <Alert severity={request.data.mode === 'QUEUED' ? 'info' : 'success'} sx={{ mt: 2 }}>
                <Typography variant="body2">{resultMessage(request.data)}</Typography>
                {request.data.mode === 'SYNC' ? (
                  <Button
                    variant="outlined"
                    size="small"
                    sx={{ mt: 1 }}
                    disabled={download.isPending}
                    onClick={() => handleDownload(request.variables)}
                  >
                    {download.isPending ? 'Preparing…' : 'Download report'}
                  </Button>
                ) : null}
              </Alert>
            ) : null}

            {download.isError ? (
              <Alert severity="error" sx={{ mt: 2 }}>
                {download.error.message}
              </Alert>
            ) : null}

            {download.isSuccess ? (
              <Alert severity={download.data.kind === 'queued' ? 'info' : 'success'} sx={{ mt: 2 }}>
                {download.data.kind === 'queued'
                  ? download.data.message
                  : `Downloaded ${download.data.fileName}.`}
              </Alert>
            ) : null}
          </CardContent>
        </Card>
      </QueryStateView>
    </ModuleScreen>
  );
}
